package dev.vhos.ble

import dev.vhos.model.PersistedLiveCanObservation
import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.MessageType

/**
 * Produces a bounded latest-per-identifier view strictly after live RAW_CAN persistence succeeds.
 * Retained log downloads and unpersisted observations cannot refresh this display-only state.
 */
object PersistedLiveCanReducer {
    const val MAX_IDENTIFIERS = 64

    fun accept(
        current: List<PersistedLiveCanObservation>,
        messageType: MessageType,
        sourceId: String,
        observation: CanObservation,
        receivedAtEpochMs: Long,
        persisted: Boolean,
        maximumIdentifiers: Int = MAX_IDENTIFIERS,
    ): List<PersistedLiveCanObservation> {
        require(maximumIdentifiers in 1..MAX_IDENTIFIERS)
        if (!persisted || messageType != MessageType.RAW_CAN_FRAME || observation.remoteRequest) {
            return current
        }
        require(observation.listenOnly) {
            "A live engineering sample must retain listen-only proof."
        }
        require(observation.dataLength in 0..8 && observation.data.size == 8) {
            "A live engineering sample has an invalid CAN payload shape."
        }

        val accepted = PersistedLiveCanObservation(
            sourceId = sourceId,
            receivedAtEpochMs = receivedAtEpochMs,
            sessionId = observation.sessionId,
            sourceSequence = observation.sourceSequence,
            gatewayMonotonicMicroseconds = observation.monotonicMicroseconds,
            bitrateBps = observation.bitrateBps,
            identifier = observation.identifier,
            extended = observation.extended,
            dataLength = observation.dataLength,
            data = observation.data.map { it.toUByte().toInt() },
        )

        val sameSource = current.filter { it.sourceId == sourceId }.toMutableList()
        sameSource.removeAll {
            it.identifier == accepted.identifier && it.extended == accepted.extended
        }
        sameSource += accepted
        return sameSource
            .sortedWith(
                compareByDescending<PersistedLiveCanObservation> { it.receivedAtEpochMs }
                    .thenByDescending { it.sourceSequence }
            )
            .take(maximumIdentifiers)
            .sortedWith(
                compareBy<PersistedLiveCanObservation> { it.extended }
                    .thenBy { it.identifier }
            )
    }
}
