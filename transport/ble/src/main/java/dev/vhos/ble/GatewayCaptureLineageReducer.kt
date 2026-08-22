package dev.vhos.ble

import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.GatewayHealth
import dev.vhos.protocol.MessageType

/**
 * Current-connection capture lineage only. A new GATT connection starts with this empty state, and
 * retained capture downloads can never make a historical record look like a live bus receipt.
 */
internal data class GatewayCaptureLineage(
    val lastVehicleFrameAtEpochMillis: Long? = null,
    val lastVehicleCanSessionId: UInt? = null,
    val lastVehicleCanSourceSequence: ULong? = null,
    val lastVehicleCanGatewayMonotonicMicroseconds: ULong? = null,
    val captureActive: Boolean? = null,
    val gatewayCaptureSessionId: UInt? = null,
)

internal object GatewayCaptureLineageReducer {
    fun acceptCan(
        current: GatewayCaptureLineage,
        messageType: MessageType,
        observations: List<CanObservation>,
        receivedAtEpochMillis: Long,
    ): GatewayCaptureLineage {
        require(messageType == MessageType.RAW_CAN_FRAME ||
            messageType == MessageType.CAPTURE_LOG_CHUNK
        ) { "Capture lineage reducer received a non-CAN message type." }
        if (messageType == MessageType.CAPTURE_LOG_CHUNK) return current
        require(receivedAtEpochMillis >= 0) { "Live CAN receipt time is invalid." }
        require(observations.size == 1) {
            "A live RAW_CAN frame must decode to exactly one observation."
        }
        val live = observations.single()
        require(live.listenOnly) { "Live CAN lineage requires listen-only proof." }
        return current.copy(
            lastVehicleFrameAtEpochMillis = receivedAtEpochMillis,
            lastVehicleCanSessionId = live.sessionId,
            lastVehicleCanSourceSequence = live.sourceSequence,
            lastVehicleCanGatewayMonotonicMicroseconds = live.monotonicMicroseconds,
        )
    }

    fun acceptHealth(
        current: GatewayCaptureLineage,
        health: GatewayHealth,
    ): GatewayCaptureLineage {
        require(health.listenOnly) { "Gateway-health lineage requires listen-only proof." }
        val captureSessionId = health.captureSessionId?.also {
            require(it in 0..UInt.MAX_VALUE.toLong()) {
                "Gateway-health capture session is outside the deployed uint32 range."
            }
        }?.toUInt()
        return current.copy(
            captureActive = health.captureActive,
            gatewayCaptureSessionId = captureSessionId,
        )
    }
}
