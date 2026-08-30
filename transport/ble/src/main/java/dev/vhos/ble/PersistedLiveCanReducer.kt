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

        // A source/capture-session change creates a new runtime truth boundary. Older activity is
        // intentionally discarded instead of being blended into the new session's rate or counts.
        val sameSession = current.filter {
            it.sourceId == sourceId && it.sessionId == observation.sessionId
        }.toMutableList()
        val previous = sameSession.firstOrNull {
            it.identifier == observation.identifier && it.extended == observation.extended
        }
        if (previous != null && (
                observation.monotonicMicroseconds <= previous.gatewayMonotonicMicroseconds ||
                    observation.sourceSequence <= previous.sourceSequence
                )
        ) {
            return bounded(sameSession, maximumIdentifiers)
        }

        val changedByteMask = previous?.let {
            changedByteMask(
                previousDataLength = it.dataLength,
                previousData = it.data,
                currentDataLength = observation.dataLength,
                currentData = observation.data.map { byte -> byte.toUByte().toInt() },
            )
        } ?: 0
        val dataLengthChanged = previous?.dataLength?.let { it != observation.dataLength } ?: false
        val payloadChanged = changedByteMask != 0 || dataLengthChanged
        val observationCount = previous?.observationCount.saturatingIncrement()
        val payloadChangeCount = previous?.payloadChangeCount
            .saturatingIncrementIf(payloadChanged)
            .coerceAtMost(observationCount - 1L)

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
            firstObservedAtEpochMs = previous?.firstObservedAtEpochMs
                ?.let { minOf(it, receivedAtEpochMs) }
                ?: receivedAtEpochMs,
            firstSourceSequence = previous?.firstSourceSequence ?: observation.sourceSequence,
            firstGatewayMonotonicMicroseconds = previous?.firstGatewayMonotonicMicroseconds
                ?: observation.monotonicMicroseconds,
            observationCount = observationCount,
            payloadChangeCount = payloadChangeCount,
            latestChangedByteMask = changedByteMask,
            latestDataLengthChanged = dataLengthChanged,
        )

        sameSession.removeAll {
            it.identifier == accepted.identifier && it.extended == accepted.extended
        }
        sameSession += accepted
        return bounded(sameSession, maximumIdentifiers)
    }

    private fun bounded(
        values: List<PersistedLiveCanObservation>,
        maximumIdentifiers: Int,
    ): List<PersistedLiveCanObservation> = values
        .sortedWith(
            compareByDescending<PersistedLiveCanObservation> { it.receivedAtEpochMs }
                .thenByDescending { it.gatewayMonotonicMicroseconds }
                .thenByDescending { it.sourceSequence }
        )
        .take(maximumIdentifiers)
        .sortedWith(
            compareBy<PersistedLiveCanObservation> { it.extended }
                .thenBy { it.identifier }
        )

    private fun changedByteMask(
        previousDataLength: Int,
        previousData: List<Int>,
        currentDataLength: Int,
        currentData: List<Int>,
    ): Int {
        val comparedLength = maxOf(previousDataLength, currentDataLength)
        return (0 until comparedLength).fold(0) { mask, index ->
            val before = previousData.getOrNull(index)?.takeIf { index < previousDataLength }
            val after = currentData.getOrNull(index)?.takeIf { index < currentDataLength }
            if (before != after) mask or (1 shl index) else mask
        }
    }

    private fun Long?.saturatingIncrement(): Long = when (this) {
        null -> 1L
        Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }

    private fun Long?.saturatingIncrementIf(condition: Boolean): Long = when {
        this == null -> 0L
        !condition -> this
        this == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }
}
