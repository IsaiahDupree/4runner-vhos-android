package dev.vhos.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.gson.Gson
import dev.vhos.model.ConnectionPhase
import dev.vhos.model.DeviceDisplayIdentity
import dev.vhos.model.DeviceRole
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.IndicatorLevel
import dev.vhos.protocol.FrameStreamDecoder
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import dev.vhos.protocol.PayloadContracts
import dev.vhos.protocol.PayloadException
import dev.vhos.protocol.ValidatedIdentity
import dev.vhos.protocol.decodeCanObservations
import dev.vhos.store.EvidenceDatabase
import dev.vhos.store.PersistedSource
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

fun interface DeviceSnapshotListener {
    fun onSnapshot(snapshot: DeviceSnapshot)
}

class DualGatewayManager(
    context: Context,
    private val database: EvidenceDatabase,
    private val listener: DeviceSnapshotListener,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val candidates = linkedMapOf<String, GatewayGattConnection>()
    private var running = false
    private var released = false
    private var scanning = false
    private var reconnects = 0L
    private var recoveryFailures = 0
    private var knownGatewaysAttempted = false
    private var radioReceiverRegistered = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
        override fun onScanFailed(errorCode: Int) = handleScanFailure(errorCode)
    }

    private val radioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED || !running || released) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    resetConnectionsAndTimers()
                    emitObd(
                        ConnectionPhase.RADIO_OFF,
                        IndicatorLevel.BLOCKED,
                        "Bluetooth turned off. Android will wait for the owner to restore the radio.",
                    )
                }
                BluetoothAdapter.STATE_ON -> {
                    recoveryFailures = 0
                    knownGatewaysAttempted = false
                    scheduleAcquisition(1_000L, "Bluetooth radio restored")
                }
            }
        }
    }

    fun hasRuntimePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        resetConnectionsAndTimers()
        running = true
        released = false
        recoveryFailures = 0
        knownGatewaysAttempted = false
        registerRadioReceiver()
        if (!hasRuntimePermissions()) {
            emitObd(
                ConnectionPhase.PERMISSION_REQUIRED,
                IndicatorLevel.BLOCKED,
                "Nearby-device permission is required for service-filtered discovery.",
            )
            return
        }
        if (adapter?.isEnabled != true) {
            emitObd(ConnectionPhase.RADIO_OFF, IndicatorLevel.BLOCKED, "Bluetooth is powered off.")
            return
        }
        beginAcquisition()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        released = false
        resetConnectionsAndTimers()
        unregisterRadioReceiver()
        emitObd(ConnectionPhase.UNAVAILABLE, IndicatorLevel.WAIT, "Vehicle session stopped.")
    }

    @SuppressLint("MissingPermission")
    fun releaseForIPhone() {
        running = false
        released = true
        resetConnectionsAndTimers()
        unregisterRadioReceiver()
        emitObd(
            ConnectionPhase.RELEASED_FOR_EXTERNAL_CLIENT,
            IndicatorLevel.WAIT,
            "Android closed GATT cleanly. The iPhone may now acquire the gateway.",
        )
    }

    @SuppressLint("MissingPermission")
    private fun beginAcquisition() {
        if (!running || released || scanning || candidates.isNotEmpty()) return
        if (!hasRuntimePermissions()) {
            emitObd(
                ConnectionPhase.PERMISSION_REQUIRED,
                IndicatorLevel.BLOCKED,
                "Nearby-device permission is required before Android can reconnect.",
            )
            return
        }
        if (adapter?.isEnabled != true) {
            emitObd(ConnectionPhase.RADIO_OFF, IndicatorLevel.BLOCKED, "Bluetooth is powered off.")
            return
        }
        if (!knownGatewaysAttempted) {
            knownGatewaysAttempted = true
            val known = knownGatewayCandidates()
            if (known.isNotEmpty()) {
                known.take(MAX_CONCURRENT_CANDIDATES).forEach(::connectKnownGateway)
                return
            }
        }
        startScanWindow()
    }

    @SuppressLint("MissingPermission")
    private fun knownGatewayCandidates(): List<KnownGatewayCandidate> {
        val found = linkedMapOf<String, KnownGatewayCandidate>()
        database.latestValidatedSources().forEach { source ->
            val device = try {
                adapter?.getRemoteDevice(source.bluetoothAddress)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (device != null) {
                found[device.address] = KnownGatewayCandidate(
                    device = device,
                    advertisedName = safeDeviceName(device),
                    roleHint = source.role,
                    expectedSource = source,
                )
            }
        }
        adapter?.bondedDevices.orEmpty().forEach { device ->
            if (found.containsKey(device.address)) return@forEach
            val name = safeDeviceName(device)
            val role = roleForApprovedName(name) ?: return@forEach
            found[device.address] = KnownGatewayCandidate(
                device = device,
                advertisedName = name,
                roleHint = role,
                expectedSource = null,
            )
        }
        return found.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun connectKnownGateway(candidate: KnownGatewayCandidate) {
        emitObd(
            ConnectionPhase.RECONNECTING,
            IndicatorLevel.ACTIVE,
            "Opening the saved ${candidate.roleHint.displayName} directly; BLE scanning is not required.",
            name = displayName(candidate.advertisedName, candidate.roleHint, candidate.expectedSource?.sourceId),
        )
        connectCandidate(
            device = candidate.device,
            initialName = candidate.advertisedName,
            initialRssi = null,
            roleHint = candidate.roleHint,
            expectedSource = candidate.expectedSource,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScanWindow() {
        if (!running || released || scanning || candidates.isNotEmpty() || !hasRuntimePermissions()) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            emitObd(ConnectionPhase.UNAVAILABLE, IndicatorLevel.BLOCKED, "BLE central scanning is unavailable.")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(VhosBleUuids.SERVICE_PARCEL).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanning = true
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            if (scanning) {
                emitObd(
                    ConnectionPhase.SCANNING,
                    IndicatorLevel.ACTIVE,
                    "Scanning for the VHOS service for ${BleRecoveryPolicy.SCAN_WINDOW_MILLIS / 1_000} seconds.",
                    recoveryAttempt = recoveryFailures,
                )
                handler.postAtTime(
                    ::onScanWindowExpired,
                    SCAN_WINDOW_TOKEN,
                    SystemClock.uptimeMillis() + BleRecoveryPolicy.SCAN_WINDOW_MILLIS,
                )
            }
        } catch (_: SecurityException) {
            scanning = false
            emitObd(
                ConnectionPhase.PERMISSION_REQUIRED,
                IndicatorLevel.BLOCKED,
                "Android revoked the nearby-device permission before the scan started.",
            )
        } catch (error: RuntimeException) {
            handleScanFailure(SCAN_FAILED_INTERNAL_ERROR, error.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacksAndMessages(SCAN_WINDOW_TOKEN)
        if (!scanning) return
        scanning = false
        if (!hasRuntimePermissions()) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: RuntimeException) {
            // The platform may already have torn down a failed scanner registration.
        }
    }

    @SuppressLint("MissingPermission")
    private fun accept(result: ScanResult) {
        if (!running || released || !scanning || candidates.containsKey(result.device.address)) return
        if (candidates.size >= MAX_CONCURRENT_CANDIDATES) return
        stopScan()
        recoveryFailures = 0
        emitObd(
            ConnectionPhase.DISCOVERED,
            IndicatorLevel.ACTIVE,
            "Candidate ${DeviceDisplayIdentity.obdName(result.device.name)} found at ${result.rssi} dBm; validating GATT.",
            name = DeviceDisplayIdentity.obdName(result.device.name),
            address = result.device.address,
            rssi = result.rssi,
        )
        connectCandidate(
            device = result.device,
            initialName = safeDeviceName(result.device),
            initialRssi = result.rssi,
            roleHint = roleForApprovedName(safeDeviceName(result.device)) ?: DeviceRole.OBD_CAN,
            expectedSource = null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectCandidate(
        device: BluetoothDevice,
        initialName: String?,
        initialRssi: Int?,
        roleHint: DeviceRole,
        expectedSource: PersistedSource?,
    ) {
        if (!running || released || candidates.containsKey(device.address)) return
        val connection = GatewayGattConnection(
            context = appContext,
            device = device,
            initialName = initialName,
            initialRssi = initialRssi,
            roleHint = roleHint,
            expectedSource = expectedSource,
            database = database,
            callback = object : GatewayConnectionCallback {
                override fun snapshot(snapshot: DeviceSnapshot) = listener.onSnapshot(snapshot)

                override fun validated(address: String, identity: ValidatedIdentity) {
                    recoveryFailures = 0
                    knownGatewaysAttempted = true
                    stopScan()
                }

                override fun disconnected(address: String, wasValidated: Boolean, reason: String) {
                    candidates.remove(address)?.close()
                    if (running && !released) {
                        reconnects++
                        recoveryFailures++
                        if (wasValidated) knownGatewaysAttempted = false
                        scheduleRecovery(
                            BleRecoveryPolicy.afterConnectionLoss(recoveryFailures),
                            reason,
                        )
                    }
                }
            },
        )
        candidates[device.address] = connection
        connection.connect(reconnects)
    }

    private fun onScanWindowExpired() {
        if (!scanning || !running || released) return
        stopScan()
        recoveryFailures++
        scheduleRecovery(
            BleRecoveryPolicy.afterNoResult(recoveryFailures),
            "No approved VHOS advertisement appeared during the bounded scan",
        )
    }

    private fun handleScanFailure(errorCode: Int, exceptionName: String? = null) {
        if (!running || released || !scanning) return
        stopScan()
        recoveryFailures++
        val errorName = BleRecoveryPolicy.scanErrorName(errorCode)
        val exceptionSuffix = exceptionName?.let { " ($it)" }.orEmpty()
        scheduleRecovery(
            decision = BleRecoveryPolicy.afterScanFailure(errorCode, recoveryFailures),
            reason = "Android BLE scanner reported $errorName ($errorCode)$exceptionSuffix",
            platformErrorCode = errorCode,
            transportErrorName = errorName,
        )
    }

    private fun scheduleRecovery(
        decision: BleRecoveryDecision,
        reason: String,
        platformErrorCode: Int? = null,
        transportErrorName: String? = null,
    ) {
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        if (!decision.automatic) {
            emitObd(
                ConnectionPhase.RECOVERY_PAUSED,
                IndicatorLevel.CHECK,
                "$reason. Automatic recovery stopped after $recoveryFailures bounded attempts; tap Start / Reacquire.",
                platformErrorCode = platformErrorCode,
                transportErrorName = transportErrorName,
                recoveryAttempt = recoveryFailures,
            )
            return
        }
        val retryAt = System.currentTimeMillis() + decision.delayMillis
        emitObd(
            ConnectionPhase.RECOVERY_COOLDOWN,
            IndicatorLevel.ACTIVE,
            "$reason. Recovery attempt $recoveryFailures/${BleRecoveryPolicy.MAX_AUTOMATIC_RECOVERY_ATTEMPTS} starts in ${decision.delayMillis / 1_000} seconds.",
            platformErrorCode = platformErrorCode,
            transportErrorName = transportErrorName,
            recoveryAttempt = recoveryFailures,
            nextRetryAtEpochMs = retryAt,
        )
        handler.postAtTime(
            ::beginAcquisition,
            RECONNECT_TOKEN,
            SystemClock.uptimeMillis() + decision.delayMillis,
        )
    }

    private fun scheduleAcquisition(delayMillis: Long, reason: String) {
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        val retryAt = System.currentTimeMillis() + delayMillis
        emitObd(
            ConnectionPhase.RECOVERY_COOLDOWN,
            IndicatorLevel.ACTIVE,
            "$reason; acquisition starts in ${delayMillis / 1_000} second.",
            nextRetryAtEpochMs = retryAt,
        )
        handler.postAtTime(
            ::beginAcquisition,
            RECONNECT_TOKEN,
            SystemClock.uptimeMillis() + delayMillis,
        )
    }

    @SuppressLint("MissingPermission")
    private fun resetConnectionsAndTimers() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        candidates.values.forEach { it.close() }
        candidates.clear()
    }

    private fun registerRadioReceiver() {
        if (radioReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(radioStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(radioStateReceiver, filter)
        }
        radioReceiverRegistered = true
    }

    private fun unregisterRadioReceiver() {
        if (!radioReceiverRegistered) return
        try {
            appContext.unregisterReceiver(radioStateReceiver)
        } catch (_: IllegalArgumentException) {
            // The process may have already unregistered during teardown.
        }
        radioReceiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    private fun roleForApprovedName(name: String?): DeviceRole? {
        val normalized = name?.uppercase() ?: return null
        return when {
            normalized.startsWith("VHOS-4R-OBD") || normalized.startsWith("VHOS-MRDIY-") -> DeviceRole.OBD_CAN
            normalized.startsWith("VHOS-4R-AC") || normalized.startsWith("VHOS-AC-") -> DeviceRole.AC_SENSOR
            else -> null
        }
    }

    private fun displayName(name: String?, role: DeviceRole, sourceId: String?): String = when (role) {
        DeviceRole.OBD_CAN -> DeviceDisplayIdentity.obdName(name, sourceId)
        DeviceRole.AC_SENSOR -> DeviceDisplayIdentity.acName(name, sourceId)
    }

    private fun emitObd(
        phase: ConnectionPhase,
        level: IndicatorLevel,
        detail: String,
        name: String? = null,
        address: String? = null,
        rssi: Int? = null,
        platformErrorCode: Int? = null,
        transportErrorName: String? = null,
        recoveryAttempt: Int = 0,
        nextRetryAtEpochMs: Long? = null,
    ) = listener.onSnapshot(
        DeviceSnapshot(
            role = DeviceRole.OBD_CAN,
            phase = phase,
            level = level,
            detail = detail,
            deviceName = name,
            deviceAddress = address,
            rssiDbm = rssi,
            reconnects = reconnects,
            platformErrorCode = platformErrorCode,
            transportErrorName = transportErrorName,
            recoveryAttempt = recoveryAttempt,
            nextRetryAtEpochMs = nextRetryAtEpochMs,
        )
    )

    companion object {
        private const val MAX_CONCURRENT_CANDIDATES = 2
        private const val SCAN_FAILED_INTERNAL_ERROR = 3
        private val RECONNECT_TOKEN = Any()
        private val SCAN_WINDOW_TOKEN = Any()
    }
}

private data class KnownGatewayCandidate(
    val device: BluetoothDevice,
    val advertisedName: String?,
    val roleHint: DeviceRole,
    val expectedSource: PersistedSource?,
)

private interface GatewayConnectionCallback {
    fun snapshot(snapshot: DeviceSnapshot)
    fun validated(address: String, identity: ValidatedIdentity)
    fun disconnected(address: String, wasValidated: Boolean, reason: String)
}

@SuppressLint("MissingPermission")
private class GatewayGattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val initialName: String?,
    private val initialRssi: Int?,
    private val roleHint: DeviceRole,
    private val expectedSource: PersistedSource?,
    private val database: EvidenceDatabase,
    private val callback: GatewayConnectionCallback,
) : BluetoothGattCallback() {
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val decoders = mutableMapOf<UUID, FrameStreamDecoder>()
    private val descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var identity: ValidatedIdentity? = null
    private var closed = false
    private var disconnectReported = false
    private var serviceDiscoveryStarted = false
    private var handshakeSent = false
    private var logicalFrames = 0L
    private var persistedFrames = 0L
    private var vehicleFrames = 0L
    private var crcFailures = 0L
    private var protocolFailures = 0L
    private var busErrors = 0L
    private var busOffEvents = 0L
    private var bitrateBps: Long? = null
    private var lastFrameAt: Long? = null
    private var reconnectCount = 0L
    private var descriptorSecurityRetries = 0

    fun connect(reconnects: Long) {
        reconnectCount = reconnects
        emit(ConnectionPhase.CONNECTING, IndicatorLevel.ACTIVE, "Opening one owner-approved BLE GATT link.")
        armFailureTimeout(
            BleRecoveryPolicy.GATT_CONNECT_TIMEOUT_MILLIS,
            "BLE GATT connection timed out before Android reached the gateway.",
        )
        try {
            gatt = device.connectGatt(
                context,
                false,
                this,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
            if (gatt == null) fail("Android did not create a BLE GATT client.")
        } catch (error: RuntimeException) {
            fail("Android could not open the BLE GATT client (${error.javaClass.simpleName}).")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        val client = gatt
        gatt = null
        try {
            client?.disconnect()
        } catch (_: RuntimeException) {
            // The vendor stack may already have removed the client.
        }
        if (client != null) {
            handler.postDelayed(
                {
                    try {
                        client.close()
                    } catch (_: RuntimeException) {
                        // Closing is best-effort after a controller failure.
                    }
                },
                GATT_CLOSE_GRACE_MILLIS,
            )
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (closed) return
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            cancelPhaseTimeout()
            emit(
                ConnectionPhase.GATT_VALIDATING,
                IndicatorLevel.ACTIVE,
                "Connected; serializing MTU negotiation before exact VHOS service discovery.",
            )
            val mtuQueued = try {
                gatt.requestMtu(PREFERRED_MTU)
            } catch (_: RuntimeException) {
                false
            }
            if (mtuQueued) {
                handler.postAtTime(
                    { if (!closed) startServiceDiscovery(gatt) },
                    PHASE_TIMEOUT_TOKEN,
                    SystemClock.uptimeMillis() + BleRecoveryPolicy.MTU_NEGOTIATION_TIMEOUT_MILLIS,
                )
            } else {
                startServiceDiscovery(gatt)
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            reportDisconnected("Gateway GATT ended with status $status and state $newState")
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (closed) return
        if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu.coerceAtLeast(23)
        if (!serviceDiscoveryStarted) {
            cancelPhaseTimeout()
            startServiceDiscovery(gatt)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (closed) return
        cancelPhaseTimeout()
        if (status != BluetoothGatt.GATT_SUCCESS) return fail("GATT service discovery failed with status $status.")
        val service = gatt.getService(VhosBleUuids.SERVICE)
            ?: return fail("Candidate does not expose the required VHOS primary service.")
        command = requiredCharacteristic(service, VhosBleUuids.COMMAND) ?: return
        val evidence = requiredCharacteristic(service, VhosBleUuids.EVIDENCE) ?: return
        val health = requiredCharacteristic(service, VhosBleUuids.HEALTH) ?: return
        val ota = requiredCharacteristic(service, VhosBleUuids.OTA_STATUS) ?: return
        val writable = command!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        if (!writable) return fail("VHOS command characteristic does not support reliable writes.")
        descriptorQueue.clear()
        listOf(evidence, health, ota).forEach { characteristic ->
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                return fail("Required characteristic ${characteristic.uuid} cannot notify.")
            }
        }
        if (!gatt.setCharacteristicNotification(evidence, true)) {
            return fail("Android rejected notification setup for the VHOS multiplexed stream.")
        }
        val descriptor = evidence.getDescriptor(VhosBleUuids.CCCD)
            ?: return fail("Required stream CCCD is absent from the VHOS evidence characteristic.")
        descriptorQueue.add(descriptor)
        decoders[evidence.uuid] = FrameStreamDecoder()
        emit(
            ConnectionPhase.SUBSCRIBING,
            IndicatorLevel.ACTIVE,
            "Enabling the single encrypted stream for evidence, health, capture, and OTA frames.",
        )
        armFailureTimeout(
            BleRecoveryPolicy.SECURE_SUBSCRIPTION_TIMEOUT_MILLIS,
            "Secure VHOS notification subscription timed out.",
        )
        writeNextDescriptor(gatt)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (closed) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (status in setOf(GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION) &&
                descriptorSecurityRetries < MAX_SECURITY_RETRIES
            ) {
                descriptorSecurityRetries++
                emit(
                    ConnectionPhase.PAIRING,
                    IndicatorLevel.ACTIVE,
                    "Securing the BLE bond; notification retry $descriptorSecurityRetries/$MAX_SECURITY_RETRIES.",
                )
                handler.postDelayed(
                    { if (!closed) writeNextDescriptor(gatt) },
                    1_500,
                )
                armFailureTimeout(
                    BleRecoveryPolicy.SECURE_SUBSCRIPTION_TIMEOUT_MILLIS,
                    "BLE pairing did not complete before the secure-subscription deadline.",
                )
                return
            }
            return fail("Secure notification subscription failed with GATT status $status.")
        }
        descriptorSecurityRetries = 0
        descriptorQueue.pollFirst()
        if (descriptorQueue.isEmpty()) {
            sendHandshake(gatt)
        } else {
            writeNextDescriptor(gatt)
        }
    }

    @Deprecated("Used by Android 12 and earlier")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        consumeNotification(characteristic.uuid, characteristic.value ?: return)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        consumeNotification(characteristic.uuid, value)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (closed) return
        if (status != BluetoothGatt.GATT_SUCCESS) return fail("Reliable command write failed with GATT status $status.")
        writeQueue.pollFirst()
        if (writeQueue.isEmpty()) {
            armFailureTimeout(
                BleRecoveryPolicy.HANDSHAKE_TIMEOUT_MILLIS,
                "The gateway did not return a CRC-valid handshake before the response deadline.",
            )
        } else {
            armFailureTimeout(
                BleRecoveryPolicy.HANDSHAKE_TIMEOUT_MILLIS,
                "Reliable handshake delivery stopped before all command chunks were acknowledged.",
            )
            writeNextCommand(gatt)
        }
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (closed || serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        cancelPhaseTimeout()
        emit(
            ConnectionPhase.GATT_VALIDATING,
            IndicatorLevel.ACTIVE,
            "Discovering the exact VHOS GATT service after serialized link setup.",
        )
        val queued = try {
            gatt.discoverServices()
        } catch (_: RuntimeException) {
            false
        }
        if (!queued) return fail("Android could not queue VHOS GATT service discovery.")
        armFailureTimeout(
            BleRecoveryPolicy.SERVICE_DISCOVERY_TIMEOUT_MILLIS,
            "VHOS GATT service discovery timed out.",
        )
    }

    private fun requiredCharacteristic(
        service: BluetoothGattService,
        uuid: UUID,
    ): BluetoothGattCharacteristic? = service.getCharacteristic(uuid) ?: run {
        fail("VHOS GATT contract is missing characteristic $uuid.")
        null
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val descriptor = descriptorQueue.peekFirst() ?: return
        val result = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (gatt.writeDescriptor(descriptor)) BluetoothGatt.GATT_SUCCESS else -1
        }
        if (result != BluetoothGatt.GATT_SUCCESS) fail("Android could not queue the secure notification descriptor.")
    }

    private fun sendHandshake(gatt: BluetoothGatt) {
        if (handshakeSent) return
        handshakeSent = true
        emit(
            ConnectionPhase.HANDSHAKING,
            IndicatorLevel.ACTIVE,
            "The encrypted multiplexed stream is active; requesting the versioned handshake.",
        )
        armFailureTimeout(
            BleRecoveryPolicy.HANDSHAKE_TIMEOUT_MILLIS,
            "Reliable handshake delivery did not complete before the deadline.",
        )
        val frame = GatewayFrame(
            messageType = MessageType.HANDSHAKE,
            sequence = 1u,
            monotonicMicroseconds = (System.nanoTime() / 1_000).toULong(),
            payload = PayloadContracts.handshakeRequestPayload(),
        ).encode()
        val maximumChunk = (mtu - 3).coerceAtLeast(20)
        frame.asList().chunked(maximumChunk).forEach { chunk ->
            writeQueue.add(chunk.toByteArray())
        }
        writeNextCommand(gatt)
    }

    private fun writeNextCommand(gatt: BluetoothGatt) {
        val bytes = writeQueue.peekFirst() ?: return
        val characteristic = command ?: return fail("Command channel disappeared before handshake.")
        val result = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else -1
        }
        if (result != BluetoothGatt.GATT_SUCCESS) fail("Android could not queue the reliable command chunk.")
    }

    private fun consumeNotification(characteristicUuid: UUID, chunk: ByteArray) {
        if (closed) return
        val decoder = decoders[characteristicUuid] ?: return fail("Notification arrived on an unvalidated characteristic.")
        val frames = try {
            decoder.append(chunk)
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("CRC32C") == true) crcFailures++ else protocolFailures++
            emit(ConnectionPhase.DEGRADED, IndicatorLevel.BLOCKED, error.message ?: "VHOS frame validation failed.")
            return
        }
        frames.forEach(::consumeFrame)
    }

    private fun consumeFrame(frame: GatewayFrame) {
        logicalFrames++
        lastFrameAt = System.currentTimeMillis()
        if (frame.messageType == MessageType.HANDSHAKE) {
            cancelPhaseTimeout()
            val validated = try {
                PayloadContracts.decodeAndValidateHandshake(frame.payload)
            } catch (error: PayloadException) {
                protocolFailures++
                return fail(error.message ?: "Gateway handshake was rejected.")
            }
            val expected = expectedSource
            if (expected != null &&
                (validated.sourceId != expected.sourceId || validated.role != expected.role)
            ) {
                protocolFailures++
                return fail(
                    "Saved gateway identity changed; expected ${expected.sourceId}/${expected.role.wireValue} " +
                        "but received ${validated.sourceId}/${validated.role.wireValue}.",
                )
            }
            identity = validated
            database.upsertValidatedSource(
                PersistedSource(
                    sourceId = validated.sourceId,
                    role = validated.role,
                    bluetoothAddress = device.address,
                    identityJson = gson.toJson(validated),
                    validatedAt = Instant.now().toString(),
                )
            )
            if (database.persistFrame(validated.sourceId, validated.role, frame, frame.encode())) persistedFrames++
            callback.validated(device.address, validated)
            emit(ConnectionPhase.STREAMING, IndicatorLevel.PASS, "Encrypted VHOS contract active; waiting for live vehicle evidence.")
            return
        }
        val source = identity ?: run {
            protocolFailures++
            return fail("Evidence arrived before a validated gateway handshake.")
        }
        if (database.persistFrame(source.sourceId, source.role, frame, frame.encode())) persistedFrames++
        when (frame.messageType) {
            MessageType.RAW_CAN_FRAME, MessageType.CAPTURE_LOG_CHUNK -> {
                val observations = try {
                    frame.decodeCanObservations()
                } catch (error: PayloadException) {
                    protocolFailures++
                    return fail(error.message ?: "CAN evidence failed validation.")
                }
                observations.forEach { observation ->
                    if (!observation.listenOnly) {
                        return fail("CAN evidence does not retain listen-only proof.")
                    }
                    val inserted = database.persistCanObservation(source.sourceId, observation)
                    if (inserted && frame.messageType == MessageType.RAW_CAN_FRAME) vehicleFrames++
                    bitrateBps = observation.bitrateBps.toLong()
                }
            }
            MessageType.GATEWAY_HEALTH -> {
                val health = try {
                    PayloadContracts.decodeHealth(frame.payload)
                } catch (error: PayloadException) {
                    protocolFailures++
                    return fail(error.message ?: "Gateway health failed validation.")
                }
                vehicleFrames = health.receivedFrames
                busErrors = health.busErrorCount
                busOffEvents = health.busOffCount
                bitrateBps = health.canBitrateBps
            }
            else -> Unit
        }
        emit(ConnectionPhase.STREAMING, IndicatorLevel.PASS, "Validated evidence is streaming and persisted locally.")
    }

    private fun fail(detail: String) {
        if (closed) return
        emit(ConnectionPhase.INCOMPATIBLE, IndicatorLevel.BLOCKED, detail)
        reportDisconnected(detail)
    }

    private fun reportDisconnected(reason: String) {
        if (disconnectReported) return
        disconnectReported = true
        val validated = identity != null
        close()
        callback.disconnected(device.address, validated, reason)
    }

    private fun armFailureTimeout(delayMillis: Long, detail: String) {
        cancelPhaseTimeout()
        handler.postAtTime(
            { if (!closed) fail(detail) },
            PHASE_TIMEOUT_TOKEN,
            SystemClock.uptimeMillis() + delayMillis,
        )
    }

    private fun cancelPhaseTimeout() {
        handler.removeCallbacksAndMessages(PHASE_TIMEOUT_TOKEN)
    }

    private fun emit(phase: ConnectionPhase, level: IndicatorLevel, detail: String) {
        val source = identity
        val role = source?.role ?: expectedSource?.role ?: roleHint
        val sourceId = source?.sourceId ?: expectedSource?.sourceId
        callback.snapshot(
            DeviceSnapshot(
                role = role,
                phase = phase,
                level = level,
                detail = detail,
                deviceName = when (role) {
                    DeviceRole.OBD_CAN -> DeviceDisplayIdentity.obdName(initialName, sourceId)
                    DeviceRole.AC_SENSOR -> DeviceDisplayIdentity.acName(initialName, sourceId)
                },
                deviceAddress = device.address,
                sourceId = source?.sourceId,
                firmwareVersion = source?.firmwareVersion,
                rssiDbm = initialRssi,
                lastFrameAtEpochMs = lastFrameAt,
                logicalFrames = logicalFrames,
                persistedFrames = persistedFrames,
                crcFailures = crcFailures,
                protocolFailures = protocolFailures,
                reconnects = reconnectCount,
                vehicleFrames = vehicleFrames,
                busErrors = busErrors,
                busOffEvents = busOffEvents,
                listenOnly = source?.listenOnly,
                bitrateBps = bitrateBps,
            )
        )
    }

    companion object {
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val MAX_SECURITY_RETRIES = 3
        private const val PREFERRED_MTU = 247
        private const val GATT_CLOSE_GRACE_MILLIS = 250L
        private val PHASE_TIMEOUT_TOKEN = Any()
    }
}
