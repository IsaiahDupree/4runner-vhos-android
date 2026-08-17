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
import android.content.Context
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
import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.FrameStreamDecoder
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import dev.vhos.protocol.PayloadContracts
import dev.vhos.protocol.PayloadException
import dev.vhos.protocol.ValidatedIdentity
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emitObd(
                ConnectionPhase.DEGRADED,
                IndicatorLevel.CHECK,
                "Android BLE scan failed with platform code $errorCode.",
            )
            scheduleScan()
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
        running = true
        released = false
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
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        released = false
        stopScan()
        candidates.values.forEach { it.close() }
        candidates.clear()
        emitObd(ConnectionPhase.UNAVAILABLE, IndicatorLevel.WAIT, "Vehicle session stopped.")
    }

    @SuppressLint("MissingPermission")
    fun releaseForIPhone() {
        running = false
        released = true
        stopScan()
        candidates.values.forEach { it.close() }
        candidates.clear()
        emitObd(
            ConnectionPhase.RELEASED_FOR_EXTERNAL_CLIENT,
            IndicatorLevel.WAIT,
            "Android closed GATT cleanly. The iPhone may now acquire the gateway.",
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running || released || scanning || !hasRuntimePermissions()) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            emitObd(ConnectionPhase.UNAVAILABLE, IndicatorLevel.BLOCKED, "BLE central scanning is unavailable.")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(VhosBleUuids.SERVICE_PARCEL).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        emitObd(
            ConnectionPhase.SCANNING,
            IndicatorLevel.ACTIVE,
            "Scanning only advertisements containing the VHOS service UUID.",
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning || !hasRuntimePermissions()) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun accept(result: ScanResult) {
        if (!running || released || candidates.containsKey(result.device.address)) return
        if (candidates.size >= MAX_CONCURRENT_CANDIDATES) return
        emitObd(
            ConnectionPhase.DISCOVERED,
            IndicatorLevel.ACTIVE,
            "Candidate ${DeviceDisplayIdentity.obdName(result.device.name)} found at ${result.rssi} dBm; validating GATT.",
            name = DeviceDisplayIdentity.obdName(result.device.name),
            address = result.device.address,
            rssi = result.rssi,
        )
        val connection = GatewayGattConnection(
            context = appContext,
            device = result.device,
            initialName = result.device.name,
            initialRssi = result.rssi,
            database = database,
            callback = object : GatewayConnectionCallback {
                override fun snapshot(snapshot: DeviceSnapshot) = listener.onSnapshot(snapshot)

                override fun validated(address: String, identity: ValidatedIdentity) {
                    if (identity.role == DeviceRole.OBD_CAN) stopScan()
                }

                override fun disconnected(address: String, wasValidated: Boolean) {
                    candidates.remove(address)?.close()
                    if (running && !released) {
                        reconnects++
                        emitObd(
                            ConnectionPhase.RECONNECTING,
                            IndicatorLevel.ACTIVE,
                            "Gateway link ended; service-filtered reacquisition starts in 2 seconds.",
                        )
                        scheduleScan()
                    }
                }
            },
        )
        candidates[result.device.address] = connection
        connection.connect(reconnects)
    }

    private fun scheduleScan() {
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        handler.postAtTime({ startScan() }, RECONNECT_TOKEN, SystemClock.uptimeMillis() + 2_000)
    }

    private fun emitObd(
        phase: ConnectionPhase,
        level: IndicatorLevel,
        detail: String,
        name: String? = null,
        address: String? = null,
        rssi: Int? = null,
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
        )
    )

    companion object {
        private const val MAX_CONCURRENT_CANDIDATES = 2
        private val RECONNECT_TOKEN = Any()
    }
}

private interface GatewayConnectionCallback {
    fun snapshot(snapshot: DeviceSnapshot)
    fun validated(address: String, identity: ValidatedIdentity)
    fun disconnected(address: String, wasValidated: Boolean)
}

@SuppressLint("MissingPermission")
private class GatewayGattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val initialName: String?,
    private val initialRssi: Int,
    private val database: EvidenceDatabase,
    private val callback: GatewayConnectionCallback,
) : BluetoothGattCallback() {
    private val gson = Gson()
    private val decoders = mutableMapOf<UUID, FrameStreamDecoder>()
    private val descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var identity: ValidatedIdentity? = null
    private var closed = false
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
        emit(ConnectionPhase.CONNECTING, IndicatorLevel.ACTIVE, "Opening a BLE GATT link.")
        gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        if (closed) return
        closed = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (closed) return
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            emit(ConnectionPhase.GATT_VALIDATING, IndicatorLevel.ACTIVE, "Connected; discovering the exact VHOS service contract.")
            gatt.requestMtu(517)
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            val validated = identity != null
            close()
            callback.disconnected(device.address, validated)
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu.coerceAtLeast(23)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (closed) return
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
            val notifiable = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!notifiable) return fail("Required characteristic ${characteristic.uuid} cannot notify.")
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                return fail("Android rejected notification setup for ${characteristic.uuid}.")
            }
            val descriptor = characteristic.getDescriptor(VhosBleUuids.CCCD)
                ?: return fail("Required CCCD is absent for ${characteristic.uuid}.")
            descriptorQueue.add(descriptor)
            decoders[characteristic.uuid] = FrameStreamDecoder()
        }
        emit(ConnectionPhase.SUBSCRIBING, IndicatorLevel.ACTIVE, "Enabling encrypted evidence, health, and OTA notifications.")
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
                Handler(Looper.getMainLooper()).postDelayed(
                    { if (!closed) writeNextDescriptor(gatt) },
                    1_500,
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
        if (status != BluetoothGatt.GATT_SUCCESS) return fail("Reliable command write failed with GATT status $status.")
        writeQueue.pollFirst()
        writeNextCommand(gatt)
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
        emit(ConnectionPhase.HANDSHAKING, IndicatorLevel.ACTIVE, "All notification channels are active; requesting the versioned handshake.")
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
            val validated = try {
                PayloadContracts.decodeAndValidateHandshake(frame.payload)
            } catch (error: PayloadException) {
                protocolFailures++
                return fail(error.message ?: "Gateway handshake was rejected.")
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
            MessageType.RAW_CAN_FRAME -> {
                val observation = try {
                    CanObservation.decodeLive(frame.payload)
                } catch (error: PayloadException) {
                    protocolFailures++
                    return fail(error.message ?: "Live CAN record failed validation.")
                }
                if (!observation.listenOnly) return fail("Live CAN record does not retain listen-only proof.")
                if (database.persistCanObservation(source.sourceId, observation)) vehicleFrames++
                bitrateBps = observation.bitrateBps.toLong()
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
        emit(ConnectionPhase.INCOMPATIBLE, IndicatorLevel.BLOCKED, detail)
        close()
    }

    private fun emit(phase: ConnectionPhase, level: IndicatorLevel, detail: String) {
        val source = identity
        callback.snapshot(
            DeviceSnapshot(
                role = source?.role ?: DeviceRole.OBD_CAN,
                phase = phase,
                level = level,
                detail = detail,
                deviceName = DeviceDisplayIdentity.obdName(initialName, source?.sourceId),
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
    }
}
