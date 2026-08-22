package dev.vhos.protocol

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.vhos.model.DeviceRole
import dev.vhos.model.VehicleMotion

data class GatewayHandshake(
    val contract: String,
    @SerializedName("contract_version") val contractVersion: String,
    @SerializedName("gateway_id") val gatewayId: String,
    @SerializedName("hardware_revision") val hardwareRevision: String,
    @SerializedName("firmware_version") val firmwareVersion: String,
    @SerializedName("firmware_build_id") val firmwareBuildId: String,
    @SerializedName("protocol_version") val protocolVersion: String,
    @SerializedName("active_config_id") val activeConfigId: String,
    @SerializedName("active_config_version") val activeConfigVersion: String,
    @SerializedName("listen_only") val listenOnly: Boolean,
    val capabilities: Set<String>,
)

data class GatewayHealth(
    val contract: String,
    @SerializedName("contract_version") val contractVersion: String,
    @SerializedName("observed_at") val observedAt: String,
    @SerializedName("vehicle_motion") val vehicleMotion: VehicleMotion,
    @SerializedName("received_frames") val receivedFrames: Long,
    @SerializedName("dropped_frames") val droppedFrames: Long,
    @SerializedName("bus_error_count") val busErrorCount: Long,
    @SerializedName("bus_off_count") val busOffCount: Long,
    @SerializedName("storage_free_bytes") val storageFreeBytes: Long?,
    @SerializedName("capture_active") val captureActive: Boolean,
    @SerializedName("capture_session_id") val captureSessionId: Long?,
    @SerializedName("listen_only") val listenOnly: Boolean,
    @SerializedName("can_bitrate_bps") val canBitrateBps: Long?,
    @SerializedName("can_passive_lock") val canPassiveLock: Boolean?,
    @SerializedName("can_scan_state") val canScanState: String?,
)

data class ValidatedIdentity(
    val role: DeviceRole,
    val sourceId: String,
    val firmwareVersion: String,
    val firmwareBuildId: String,
    val hardwareRevision: String,
    val protocolVersion: String,
    val capabilities: Set<String>,
    val listenOnly: Boolean,
)

object PayloadContracts {
    private val gson = Gson()

    fun handshakeRequestPayload(): ByteArray =
        "{\"contract\":\"gateway.handshake.request\",\"contract_version\":\"1.0.0\"}"
            .toByteArray(Charsets.UTF_8)

    fun decodeAndValidateHandshake(payload: ByteArray): ValidatedIdentity = try {
        decodeAndValidateHandshakeUnsafe(payload)
    } catch (error: PayloadException) {
        throw error
    } catch (error: RuntimeException) {
        throw PayloadException("Handshake fields are incomplete or have invalid types.", error)
    }

    private fun decodeAndValidateHandshakeUnsafe(payload: ByteArray): ValidatedIdentity {
        val handshake = try {
            gson.fromJson(payload.toString(Charsets.UTF_8), GatewayHandshake::class.java)
        } catch (error: RuntimeException) {
            throw PayloadException("Handshake JSON is invalid.", error)
        }
        requireContract(handshake.contract, "gateway.handshake")
        if (handshake.contractVersion != "1.0.0") {
            throw PayloadException("Unsupported handshake contract ${handshake.contractVersion}.")
        }
        if (handshake.gatewayId.isBlank() || handshake.firmwareBuildId.isBlank() ||
            handshake.hardwareRevision.isBlank() || handshake.protocolVersion.isBlank()
        ) {
            throw PayloadException("Handshake identity fields are incomplete.")
        }
        if (!handshake.listenOnly) {
            throw PayloadException("OBD gateway did not prove listen-only operation.")
        }
        val required = setOf("capture.passive", "evidence.export")
        if (!handshake.capabilities.containsAll(required)) {
            throw PayloadException("OBD gateway is missing required capabilities: ${required - handshake.capabilities}.")
        }
        return ValidatedIdentity(
            role = DeviceRole.OBD_CAN,
            sourceId = handshake.gatewayId,
            firmwareVersion = handshake.firmwareVersion,
            firmwareBuildId = handshake.firmwareBuildId,
            hardwareRevision = handshake.hardwareRevision,
            protocolVersion = handshake.protocolVersion,
            capabilities = handshake.capabilities,
            listenOnly = true,
        )
    }

    fun decodeHealth(payload: ByteArray): GatewayHealth = try {
        decodeHealthUnsafe(payload)
    } catch (error: PayloadException) {
        throw error
    } catch (error: RuntimeException) {
        throw PayloadException("Gateway health fields are incomplete or have invalid types.", error)
    }

    private fun decodeHealthUnsafe(payload: ByteArray): GatewayHealth {
        val health = try {
            gson.fromJson(payload.toString(Charsets.UTF_8), GatewayHealth::class.java)
        } catch (error: RuntimeException) {
            throw PayloadException("Gateway health JSON is invalid.", error)
        }
        requireContract(health.contract, "gateway.health")
        if (health.contractVersion != "1.0.0") {
            throw PayloadException("Unsupported health contract ${health.contractVersion}.")
        }
        if (health.vehicleMotion !in VehicleMotion.entries) {
            throw PayloadException("Gateway health vehicle motion is invalid.")
        }
        if (!health.listenOnly) throw PayloadException("Live health no longer proves listen-only operation.")
        if (health.captureSessionId != null && health.captureSessionId !in 0..UInt.MAX_VALUE.toLong()) {
            throw PayloadException("Gateway health capture session is outside the deployed uint32 range.")
        }
        return health
    }

    private fun requireContract(actual: String?, expected: String) {
        if (actual != expected) throw PayloadException("Expected $expected, received ${actual ?: "no contract"}.")
    }
}

class PayloadException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
