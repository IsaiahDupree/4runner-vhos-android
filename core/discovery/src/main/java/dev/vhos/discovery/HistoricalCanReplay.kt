package dev.vhos.discovery

import dev.vhos.protocol.FrameStreamDecoder
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import dev.vhos.protocol.decodeCanObservations

const val HISTORICAL_REPLAY_LABEL = "HISTORICAL REPLAY • NOT LIVE"
const val HISTORICAL_REPLAY_SOURCE = "REAL_CAPTURE_REPLAY"

enum class ReplayFaultProfile {
    CLEAN,
    DROP_FRAGMENT,
    CORRUPT_PAYLOAD,
    DISCONNECT_MID_FRAME,
}

data class HistoricalReplayProgress(
    val recordIndex: Int,
    val totalExpectedRecords: Int,
    val sourceId: String,
    val record: dev.vhos.protocol.CanObservation,
    val sourceCaptureOffsetMicroseconds: ULong,
    val decoderRecoveries: Long,
    val decoderDiscardedBytes: Long,
)

data class HistoricalReplayReport(
    val label: String,
    val sourceClassification: String,
    val inputRecords: Int,
    val repeat: Int,
    val expectedRecordsAfterFaults: Int,
    val decodedRecords: Int,
    val expectedMissingRecords: Int,
    val faultedWireFrames: Int,
    val sessions: Int,
    val uniqueIdentifiers: Int,
    val sourceDurationMicroseconds: ULong,
    val decoderRecoveries: Long,
    val decoderCorruptCandidates: Long,
    val decoderDiscardedBytes: Long,
    val exactRecordOrderAndPayloadMatch: Boolean,
    val cancelled: Boolean,
) {
    val passed: Boolean get() = !cancelled && exactRecordOrderAndPayloadMatch
}

/**
 * Reconstructs the deployed VHOS live-record envelope from persisted real evidence, then sends it
 * through the production frame encoder, hostile fragmentation, stream decoder, and CAN decoder.
 * It never writes to the evidence database and never promotes raw bytes to vehicle meanings.
 */
object HistoricalCanReplay {
    private val defaultFragmentSizes = intArrayOf(1, 3, 20, 244, 5, 509, 64, 17, 1_024)

    fun run(
        input: List<DiscoveryObservation>,
        repeat: Int = 1,
        fault: ReplayFaultProfile = ReplayFaultProfile.CLEAN,
        faultInterval: Int = 257,
        fragmentSizes: IntArray = defaultFragmentSizes,
        shouldContinue: () -> Boolean = { true },
        onRecord: (HistoricalReplayProgress) -> Unit = {},
    ): HistoricalReplayReport {
        require(input.isNotEmpty()) { "No persisted CAN observations are available for replay." }
        require(repeat in 1..10_000) { "Replay repeat must be between 1 and 10,000." }
        require(faultInterval >= 2) { "Replay fault interval must be at least 2." }
        require(fragmentSizes.isNotEmpty() && fragmentSizes.all { it > 0 }) {
            "Replay fragment sizes must be positive."
        }
        require(input.all { it.sourceId.isNotBlank() && it.observation.listenOnly }) {
            "Historical replay requires source identity and listen-only proof for every record."
        }
        val ordered = input.sortedWith(
            compareBy<DiscoveryObservation> { it.sourceId }
                .thenBy { it.observation.sessionId }
                .thenBy { it.observation.monotonicMicroseconds }
                .thenBy { it.observation.sourceSequence }
        )
        require(
            ordered.distinctBy {
                Triple(it.sourceId, it.observation.sessionId, it.observation.sourceSequence)
            }.size == ordered.size
        ) { "Historical replay refuses duplicate evidence identities." }
        require(
            ordered.distinctBy {
                it.observation.sessionId to it.observation.sourceSequence
            }.size == ordered.size
        ) { "Historical replay wire identity is ambiguous across sources." }

        val sourceByWireIdentity = ordered.associate {
            (it.observation.sessionId to it.observation.sourceSequence) to it.sourceId
        }
        val captureOffsets = captureOffsets(ordered)
        val sourceDuration = captureOffsets.last()
        val expected = mutableListOf<DiscoveryObservation>()
        val decoded = mutableListOf<DiscoveryObservation>()
        val decoder = FrameStreamDecoder()
        var faultedFrames = 0
        var cancelled = false
        val totalUnits = ordered.size * repeat

        run replayLoop@{
            var unitIndex = 0
            repeat(repeat) { repetition ->
                ordered.forEachIndexed { recordIndex, source ->
                    if (!shouldContinue()) {
                        cancelled = true
                        return@replayLoop
                    }
                    unitIndex++
                    val outerSequence = unitIndex.toULong()
                    val wire = GatewayFrame(
                        messageType = MessageType.RAW_CAN_FRAME,
                        sequence = outerSequence,
                        monotonicMicroseconds = outerSequence * 2_000UL,
                        payload = source.observation.encodeLive(),
                    ).encode()
                    val faultThisFrame = fault != ReplayFaultProfile.CLEAN &&
                        unitIndex % faultInterval == 0 && unitIndex < totalUnits
                    if (!faultThisFrame) expected += source else faultedFrames++

                    when {
                        !faultThisFrame -> feed(
                            wire,
                            fragmentSizes,
                            decoder,
                            sourceByWireIdentity,
                            decoded,
                            totalUnits,
                            captureOffsets[recordIndex] + repetition.toULong() * (sourceDuration + 1UL),
                            onRecord,
                        )
                        fault == ReplayFaultProfile.DROP_FRAGMENT -> {
                            val start = minOf(GatewayFrame.HEADER_LENGTH + 5, wire.size - 2)
                            val width = minOf(16, maxOf(1, wire.size - start - 1))
                            val damaged = wire.copyOfRange(0, start) +
                                wire.copyOfRange(start + width, wire.size)
                            feed(
                                damaged, fragmentSizes, decoder, sourceByWireIdentity, decoded,
                                totalUnits, captureOffsets[recordIndex], onRecord,
                            )
                        }
                        fault == ReplayFaultProfile.CORRUPT_PAYLOAD -> {
                            val damaged = wire.copyOf().also { bytes ->
                                val offset = minOf(GatewayFrame.HEADER_LENGTH + 4, bytes.lastIndex)
                                bytes[offset] = (bytes[offset].toInt() xor 0x80).toByte()
                            }
                            feed(
                                damaged, fragmentSizes, decoder, sourceByWireIdentity, decoded,
                                totalUnits, captureOffsets[recordIndex], onRecord,
                            )
                        }
                        else -> {
                            val split = minOf(GatewayFrame.HEADER_LENGTH + 5, wire.lastIndex)
                            feed(
                                wire.copyOfRange(0, split), fragmentSizes, decoder,
                                sourceByWireIdentity, decoded, totalUnits,
                                captureOffsets[recordIndex], onRecord,
                            )
                            decoder.resetBufferPreservingDiagnostics()
                        }
                    }
                }
            }
        }

        val exact = decoded == expected
        return HistoricalReplayReport(
            label = HISTORICAL_REPLAY_LABEL,
            sourceClassification = HISTORICAL_REPLAY_SOURCE,
            inputRecords = totalUnits,
            repeat = repeat,
            expectedRecordsAfterFaults = expected.size,
            decodedRecords = decoded.size,
            expectedMissingRecords = totalUnits - expected.size,
            faultedWireFrames = faultedFrames,
            sessions = ordered.map { it.sourceId to it.observation.sessionId }.toSet().size,
            uniqueIdentifiers = ordered.map {
                it.observation.identifier to it.observation.extended
            }.toSet().size,
            sourceDurationMicroseconds = sourceDuration,
            decoderRecoveries = decoder.recoveryCount,
            decoderCorruptCandidates = decoder.corruptCandidateCount,
            decoderDiscardedBytes = decoder.discardedByteCount,
            exactRecordOrderAndPayloadMatch = exact,
            cancelled = cancelled,
        )
    }

    private fun feed(
        wire: ByteArray,
        fragmentSizes: IntArray,
        decoder: FrameStreamDecoder,
        sourceByWireIdentity: Map<Pair<UInt, ULong>, String>,
        decoded: MutableList<DiscoveryObservation>,
        totalExpectedRecords: Int,
        captureOffset: ULong,
        onRecord: (HistoricalReplayProgress) -> Unit,
    ) {
        var offset = 0
        var fragment = 0
        while (offset < wire.size) {
            val count = minOf(fragmentSizes[fragment % fragmentSizes.size], wire.size - offset)
            decoder.append(wire.copyOfRange(offset, offset + count)).forEach { frame ->
                frame.decodeCanObservations().forEach { observation ->
                    val sourceId = checkNotNull(
                        sourceByWireIdentity[observation.sessionId to observation.sourceSequence]
                    ) { "Decoded replay record has no immutable source identity." }
                    val item = DiscoveryObservation(sourceId, observation)
                    decoded += item
                    onRecord(
                        HistoricalReplayProgress(
                            recordIndex = decoded.size,
                            totalExpectedRecords = totalExpectedRecords,
                            sourceId = sourceId,
                            record = observation,
                            sourceCaptureOffsetMicroseconds = captureOffset,
                            decoderRecoveries = decoder.recoveryCount,
                            decoderDiscardedBytes = decoder.discardedByteCount,
                        )
                    )
                }
            }
            offset += count
            fragment++
        }
    }

    private fun captureOffsets(records: List<DiscoveryObservation>): List<ULong> {
        val result = MutableList(records.size) { 0UL }
        val indexByIdentity = records.mapIndexed { index, item ->
            Triple(item.sourceId, item.observation.sessionId, item.observation.sourceSequence) to index
        }.toMap()
        var accumulated = 0UL
        records.groupBy { it.sourceId to it.observation.sessionId }
            .toSortedMap(compareBy<Pair<String, UInt>> { it.first }.thenBy { it.second })
            .values
            .forEach { session ->
                val first = session.minOf { it.observation.monotonicMicroseconds }
                val last = session.maxOf { it.observation.monotonicMicroseconds }
                session.forEach { item ->
                    val index = checkNotNull(
                        indexByIdentity[
                            Triple(item.sourceId, item.observation.sessionId, item.observation.sourceSequence)
                        ]
                    )
                    result[index] = accumulated + item.observation.monotonicMicroseconds - first
                }
                accumulated += last - first + 1_000_000UL
            }
        return result
    }
}
