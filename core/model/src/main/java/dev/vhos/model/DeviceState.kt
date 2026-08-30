package dev.vhos.model

enum class DeviceRole(val wireValue: String, val displayName: String) {
    OBD_CAN("OBD_CAN", "OBD / CAN gateway"),
    AC_SENSOR("AC_SENSOR", "A/C sensor node"),
}

enum class ConnectionPhase(val displayName: String) {
    UNAVAILABLE("Unavailable"),
    RADIO_OFF("Bluetooth off"),
    PERMISSION_REQUIRED("Permission required"),
    SCANNING("Scanning"),
    DISCOVERED("Discovered"),
    CONNECTING("Connecting"),
    GATT_VALIDATING("Validating GATT"),
    PAIRING("Securing link"),
    SUBSCRIBING("Subscribing"),
    HANDSHAKING("Negotiating contract"),
    STREAMING("Streaming"),
    DEGRADED("Degraded"),
    RECONNECTING("Reconnecting"),
    RECOVERY_COOLDOWN("Recovery cooldown"),
    RECOVERY_PAUSED("Recovery paused"),
    RELEASED_FOR_EXTERNAL_CLIENT("Released for iPhone"),
    INCOMPATIBLE("Incompatible"),
    FIRMWARE_NOT_READY("Firmware not ready"),
}

enum class IndicatorLevel { PASS, ACTIVE, WAIT, CHECK, BLOCKED }

/**
 * Motion authority reported by a validated gateway-health frame. UNKNOWN is the fail-closed
 * default and must never be treated as equivalent to zero vehicle speed.
 */
enum class VehicleMotion { UNKNOWN, PARKED, MOVING }

data class StandardObdReading(
    val ecuAddress: String,
    val pid: Int,
    val signalId: String,
    val name: String,
    val value: Double,
    val unit: String,
    val observedAt: String,
    val gatewayMonotonicMicroseconds: ULong,
    val sourceSequence: ULong,
    val definitionRevision: String,
)

/**
 * Latest accepted live CAN observation after the complete VHOS frame and raw record were validated
 * and the observation was durably inserted into the local evidence store.
 *
 * This is a bounded, session-local display projection. It is not restored after disconnect, is not
 * a replacement for append-only evidence, and carries no accepted vehicle-signal meaning.
 */
data class PersistedLiveCanObservation(
    val sourceId: String,
    val receivedAtEpochMs: Long,
    val sessionId: UInt,
    val sourceSequence: ULong,
    val gatewayMonotonicMicroseconds: ULong,
    val bitrateBps: Int,
    val identifier: UInt,
    val extended: Boolean,
    val dataLength: Int,
    /** Eight-byte CAN storage shape; only [dataLength] bytes are authoritative payload. */
    val data: List<Int>,
) {
    init {
        require(sourceId.isNotBlank()) { "Live CAN source identity is required." }
        require(receivedAtEpochMs > 0) { "Live CAN receipt time is invalid." }
        require(bitrateBps == 250_000 || bitrateBps == 500_000) {
            "Live CAN bitrate is unsupported."
        }
        require(identifier <= 0x1FFF_FFFFu && (extended || identifier <= 0x7FFu)) {
            "Live CAN identifier is out of range."
        }
        require(dataLength in 0..8 && data.size == 8 && data.all { it in 0..255 }) {
            "Live CAN payload shape is invalid."
        }
    }
}

data class DeviceSnapshot(
    val role: DeviceRole,
    val phase: ConnectionPhase,
    val level: IndicatorLevel,
    val detail: String,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val sourceId: String? = null,
    val firmwareVersion: String? = null,
    val rssiDbm: Int? = null,
    val lastFrameAtEpochMs: Long? = null,
    val logicalFrames: Long = 0,
    val persistedFrames: Long = 0,
    val crcFailures: Long = 0,
    val protocolFailures: Long = 0,
    val reconnects: Long = 0,
    val vehicleFrames: Long = 0,
    /** Receipt time of the newest validated live RAW_CAN frame; retained log chunks do not update it. */
    val lastVehicleFrameAtEpochMs: Long? = null,
    val busErrors: Long = 0,
    val busOffEvents: Long = 0,
    val listenOnly: Boolean? = null,
    /** Capabilities from the current validated VHOS handshake; never restored from display state. */
    val gatewayCapabilities: Set<String> = emptySet(),
    val bitrateBps: Long? = null,
    val vehicleMotion: VehicleMotion = VehicleMotion.UNKNOWN,
    val vehicleMotionObservedAtEpochMs: Long? = null,
    val vehicleMotionFrameSequence: ULong? = null,
    val vehicleMotionGatewayMonotonicMicroseconds: ULong? = null,
    /** Recorder state and lineage from the newest validated gateway-health frame. */
    val captureActive: Boolean? = null,
    val gatewayCaptureSessionId: UInt? = null,
    /** Exact lineage of the newest validated live RAW_CAN record (never a retained log chunk). */
    val lastVehicleCanSessionId: UInt? = null,
    val lastVehicleCanSourceSequence: ULong? = null,
    val lastVehicleCanGatewayMonotonicMicroseconds: ULong? = null,
    val platformErrorCode: Int? = null,
    val transportErrorName: String? = null,
    val recoveryAttempt: Int = 0,
    val nextRetryAtEpochMs: Long? = null,
    val j1979EcuCount: Int = 0,
    val j1979EnumerationComplete: Boolean? = null,
    val j1979SupportedPidCount: Int = 0,
    val standardObdReadings: List<StandardObdReading> = emptyList(),
    /** Latest-per-identifier persisted live RAW_CAN observations; never retained-history chunks. */
    val liveCanObservations: List<PersistedLiveCanObservation> = emptyList(),
) {
    companion object {
        fun initial(role: DeviceRole): DeviceSnapshot = when (role) {
            DeviceRole.OBD_CAN -> DeviceSnapshot(
                role = role,
                phase = ConnectionPhase.UNAVAILABLE,
                level = IndicatorLevel.WAIT,
                detail = "Start a vehicle session to scan for the approved gateway.",
            )
            DeviceRole.AC_SENSOR -> DeviceSnapshot(
                role = role,
                phase = ConnectionPhase.FIRMWARE_NOT_READY,
                level = IndicatorLevel.WAIT,
                detail = "The current A/C ESP32 recovery image does not advertise the VHOS BLE service.",
            )
        }
    }
}

data class HeadUnitSnapshot(
    val running: Boolean = false,
    val status: String = "Vehicle session stopped.",
    val obd: DeviceSnapshot = DeviceSnapshot.initial(DeviceRole.OBD_CAN),
    val ac: DeviceSnapshot = DeviceSnapshot.initial(DeviceRole.AC_SENSOR),
    val storedLogicalFrames: Long = 0,
    val storedCanObservations: Long = 0,
    val lastExportAtEpochMs: Long? = null,
    val lastImportAtEpochMs: Long? = null,
)
