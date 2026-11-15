package dev.vhos.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import android.os.ParcelUuid
import com.google.gson.Gson
import dev.vhos.model.ACBatteryReading
import dev.vhos.model.ACBatteryResult
import dev.vhos.model.ConnectionPhase
import dev.vhos.model.DeviceRole
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.IndicatorLevel
import dev.vhos.protocol.ACBatteryIdentity
import dev.vhos.protocol.ACBatteryPayloads
import dev.vhos.protocol.MessageType
import dev.vhos.store.EvidenceDatabase
import dev.vhos.store.PersistedSource
import java.time.Instant
import java.util.UUID

/** A battery-only GATT session with its own discovery, sequence, reconnects and evidence. */
@SuppressLint("MissingPermission")
class ACBatteryMonitor(
    context: Context,
    private val database: EvidenceDatabase,
    private val listener: DeviceSnapshotListener,
) {
    private val context = context.applicationContext
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var active = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var sample: BluetoothGattCharacteristic? = null
    private var identity: ACBatteryIdentity? = null
    private var lastSequence = 0uL
    private var lastDeviceTime = 0uL
    private var stored = 0L
    private var attempts = 0
    private val poll = Runnable {
        val link = gatt ?: return@Runnable
        val characteristic = sample ?: return@Runnable
        if (!link.readCharacteristic(characteristic)) retry("Could not read the A/C battery.")
    }
    private val expired = Runnable { retry("A/C battery signal expired.") }
    private val rediscover = Runnable { discover() }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!active || !scanning || gatt != null ||
                    result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE)) != true
                ) return@post
                stopScan()
                emit(ConnectionPhase.CONNECTING, "Connecting to the A/C logger.")
                try {
                    gatt = result.device.connectGatt(context, false, client, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, main)
                    if (gatt == null) retry("Could not open the A/C logger link.")
                } catch (_: SecurityException) {
                    retry("Bluetooth permission was withdrawn.")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { if (scanning) retry("A/C scan failed ($errorCode).") }
        }
    }

    private val client = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            if (link !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                retry("A/C logger disconnected.")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && !link.discoverServices()) {
                retry("A/C service discovery could not start.")
            }
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            if (link !== gatt) return
            val service = link.getService(SERVICE)
            val claim = service?.getCharacteristic(IDENTITY)
            val readings = service?.getCharacteristic(SAMPLE)
            if (status != BluetoothGatt.GATT_SUCCESS || claim == null || readings == null ||
                claim.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
                readings.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
            ) {
                return retry("A/C battery GATT contract is unavailable.")
            }
            sample = readings
            emit(ConnectionPhase.PAIRING, "Reading the encrypted A/C identity. Accept Bluetooth pairing if prompted.")
            if (!link.readCharacteristic(claim)) retry("A/C battery handshake could not start.")
        }

        override fun onCharacteristicRead(link: BluetoothGatt, item: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) {
            acceptRead(link, item.uuid, bytes, status)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(link: BluetoothGatt, item: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) acceptRead(link, item.uuid, item.value ?: byteArrayOf(), status)
        }
    }

    fun start() = main.post {
        clear()
        active = true
        attempts = 0
        discover()
    }

    fun stop() = main.post {
        active = false
        clear()
        emit(ConnectionPhase.UNAVAILABLE, "A/C battery disconnected.")
    }

    private fun hasPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun discover() {
        if (!active) return
        if (!hasPermission()) return emit(ConnectionPhase.PERMISSION_REQUIRED, "Bluetooth permission is required for battery readings.")
        val scanner = adapter?.bluetoothLeScanner
            ?: return emit(ConnectionPhase.RADIO_OFF, "Enable Bluetooth to read the A/C battery.")
        scanning = true
        emit(ConnectionPhase.SCANNING, "Looking for the A/C logger in RUN.")
        armDeadline(24_000)
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scan,
            )
        } catch (_: RuntimeException) {
            retry("Could not start A/C battery discovery.")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        if (hasPermission()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    private fun clear() {
        stopScan()
        main.removeCallbacks(expired)
        main.removeCallbacks(poll)
        main.removeCallbacks(rediscover)
        val link = gatt
        gatt = null
        link?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        identity = null
        sample = null
        lastSequence = 0uL
        lastDeviceTime = 0uL
        stored = 0
    }

    private fun retry(message: String) {
        clear()
        if (!active) return
        if (++attempts > 3) {
            emit(ConnectionPhase.UNAVAILABLE, "$message Use Start / Reacquire when the logger is ready.")
            return
        }
        emit(ConnectionPhase.RECONNECTING, message)
        main.postDelayed(rediscover, 4_000)
    }

    private fun armDeadline(millis: Long = 18_000) {
        main.removeCallbacks(expired)
        main.postDelayed(expired, millis)
    }

    private fun acceptRead(link: BluetoothGatt, uuid: UUID, raw: ByteArray, status: Int) {
        if (link !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) return retry("A/C battery read did not complete.")
        try {
            when (uuid) {
                IDENTITY -> {
                    check(identity == null && link.device.bondState == BluetoothDevice.BOND_BONDED)
                    val frame = ACBatteryPayloads.frame(raw, MessageType.HANDSHAKE)
                    val claim = ACBatteryPayloads.identity(frame)
                    val bindings = context.getSharedPreferences("ac_battery_identities", Context.MODE_PRIVATE)
                    val known = bindings.getString(link.device.address, null)
                    check(known == null || known == claim.sourceId)
                    database.upsertValidatedSource(PersistedSource(claim.sourceId, DeviceRole.AC_SENSOR,
                        link.device.address, gson.toJson(claim), Instant.now().toString()))
                    database.resolveEvidenceScope(claim.sourceId)?.let { scope ->
                        if (database.persistFrame(scope, DeviceRole.AC_SENSOR, frame, raw)) ++stored
                    }
                    bindings.edit().putString(link.device.address, claim.sourceId).apply()
                    identity = claim
                    lastSequence = frame.sequence
                    lastDeviceTime = frame.monotonicMicroseconds
                    armDeadline()
                    poll.run()
                }
                SAMPLE -> {
                    val claim = checkNotNull(identity)
                    val frame = ACBatteryPayloads.frame(raw, MessageType.AC_BATTERY)
                    if (frame.sequence > lastSequence && frame.monotonicMicroseconds > lastDeviceTime) {
                        val scope = database.resolveEvidenceScope(claim.sourceId)
                        if (scope != null && database.persistFrame(scope, DeviceRole.AC_SENSOR, frame, raw)) {
                            val reading = ACBatteryPayloads.reading(frame)
                            ++stored
                            emit(ConnectionPhase.STREAMING, "A/C battery telemetry", reading)
                        } else {
                            emit(ConnectionPhase.DEGRADED, "A verified vehicle profile and new evidence are required for battery readings.")
                        }
                        lastSequence = frame.sequence
                        lastDeviceTime = frame.monotonicMicroseconds
                        attempts = 0
                        armDeadline()
                    }
                    main.postDelayed(poll, 5_000)
                }
                else -> throw IllegalStateException("Unexpected A/C battery characteristic")
            }
        } catch (_: RuntimeException) {
            retry("Could not verify or retain A/C battery evidence.")
        }
    }

    private fun emit(phase: ConnectionPhase, message: String, reading: ACBatteryReading? = null) {
        listener.onSnapshot(DeviceSnapshot(
            role = DeviceRole.AC_SENSOR, phase = phase, detail = message,
            level = when {
                reading?.result == ACBatteryResult.VALID -> IndicatorLevel.PASS
                reading != null -> IndicatorLevel.CHECK
                else -> IndicatorLevel.WAIT
            },
            sourceId = identity?.sourceId, firmwareVersion = identity?.buildId,
            persistedFrames = stored, acBattery = reading,
            lastFrameAtEpochMs = reading?.let { System.currentTimeMillis() },
        ))
    }

    companion object {
        private val SERVICE = UUID.fromString("CB09443D-4F7E-4529-A8E6-E84391864ACE")
        private val IDENTITY = UUID.fromString("A81A38E3-C36C-4EC7-80CC-9199C922E624")
        private val SAMPLE = UUID.fromString("9686641E-3831-428A-891F-BCFE4846B0D9")
    }
}
