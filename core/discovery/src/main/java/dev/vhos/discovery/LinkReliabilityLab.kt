package dev.vhos.discovery

import dev.vhos.protocol.FrameStreamDecoder
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import dev.vhos.protocol.decodeCanObservations

const val LINK_RELIABILITY_LABEL = "OFFLINE LINK RELIABILITY LAB • NOT LIVE"
const val LINK_RELIABILITY_CONTRACT_VERSION = "1.0.0"
const val LINK_RELIABILITY_MAXIMUM_BUFFER_BYTES = 262_144
const val LINK_RELIABILITY_SUPERVISION_BUDGET_MILLIS = 18_000L

enum class LinkQuality { HEALTHY, DEGRADED }

enum class LinkReliabilityScenario(
    val expectedQuality: LinkQuality,
    val fragmentSizes: IntArray,
    val interval: Int = 0,
    val burstFrames: Int = 1,
    val stallMillis: Long = 0L,
) {
    CLEAN_SOAK(LinkQuality.HEALTHY, intArrayOf(1, 3, 20, 244, 5, 509, 64, 17, 1_024)),
    ATT_MTU_23(LinkQuality.HEALTHY, intArrayOf(20)),
    MTU_CHURN(LinkQuality.HEALTHY, intArrayOf(20, 61, 97, 185, 244)),
    BURST_DELIVERY(LinkQuality.HEALTHY, intArrayOf(1_048_576), burstFrames = 64),
    JITTER_AND_SHORT_STALL(
        LinkQuality.HEALTHY,
        intArrayOf(1, 3, 20, 244, 5, 509, 64, 17, 1_024),
        interval = 101,
        stallMillis = 2_500,
    ),
    DUPLICATE_FRAME(LinkQuality.DEGRADED, intArrayOf(1, 3, 20, 244, 64), interval = 37),
    DUPLICATE_NOTIFICATION(LinkQuality.DEGRADED, intArrayOf(20), interval = 43),
    NOTIFICATION_LOSS(LinkQuality.DEGRADED, intArrayOf(20), interval = 41),
    PAYLOAD_CORRUPTION(LinkQuality.DEGRADED, intArrayOf(1, 3, 20, 244, 64), interval = 43),
    NOTIFICATION_REORDER(LinkQuality.DEGRADED, intArrayOf(20), interval = 47),
    MID_FRAME_RECONNECT(LinkQuality.DEGRADED, intArrayOf(20), interval = 53),
    STALE_PRIOR_EPOCH(LinkQuality.DEGRADED, intArrayOf(1, 3, 20, 244, 64), interval = 59),
    SUPERVISION_TIMEOUT_RECOVERY(
        LinkQuality.DEGRADED,
        intArrayOf(1, 3, 20, 244, 64),
        interval = 211,
        stallMillis = 20_000,
    ),
    BOUNDED_QUEUE_OVERRUN(
        LinkQuality.DEGRADED,
        intArrayOf(1, 3, 20, 244, 64),
        interval = 97,
    ),
    MIXED_INTERFERENCE(LinkQuality.DEGRADED, intArrayOf(20, 61, 185, 244)),
}

data class LinkReliabilityScenarioReport(
    val scenario: LinkReliabilityScenario,
    val expectedQuality: LinkQuality,
    val observedQuality: LinkQuality,
    val cycles: Int,
    val wireDeliveries: Int,
    val uniqueInputRecords: Int,
    val expectedUniqueRecords: Int,
    val acceptedUniqueRecords: Int,
    val inducedLostWireFrames: Int,
    val duplicateIdentityRejections: Long,
    val staleEpochNotificationRejections: Long,
    val outerSequenceRegressionRejections: Long,
    val outerSequenceGaps: ULong,
    val reconnects: Int,
    val maximumStallMillis: Long,
    val notificationFragments: Long,
    val decoderRecoveries: Long,
    val decoderCorruptCandidates: Long,
    val decoderDiscardedBytes: Long,
    val decoderBufferedBytes: Int,
    val decoderMaximumBufferedBytes: Int,
    val exactExpectedSurvivorOrderAndPayload: Boolean,
    val passed: Boolean,
)

data class LinkReliabilityMatrixReport(
    val contractVersion: String,
    val label: String,
    val sourceClassification: String,
    val soakCycles: Int,
    val scenarios: List<LinkReliabilityScenarioReport>,
    val authority: String,
) {
    val passed: Boolean get() = scenarios.all { it.passed }
    val healthyScenarios: Int get() = scenarios.count { it.observedQuality == LinkQuality.HEALTHY }
    val degradedScenarios: Int get() = scenarios.count { it.observedQuality == LinkQuality.DEGRADED }
    val totalWireDeliveries: Int get() = scenarios.sumOf { it.wireDeliveries }
}

/**
 * Runs deterministic impairment tests against immutable real observations already on Android.
 * This does not impersonate a radio, ESP32 controller, or Android vendor BLE stack.
 */
object LinkReliabilityLab {
    fun run(
        input: List<DiscoveryObservation>,
        soakCycles: Int = 20,
        shouldContinue: () -> Boolean = { true },
    ): LinkReliabilityMatrixReport {
        require(input.isNotEmpty()) { "No persisted CAN observations are available for the link lab." }
        require(soakCycles in 1..1_000) { "Soak cycles must be between 1 and 1,000." }
        require(input.all { it.sourceId.isNotBlank() && it.observation.listenOnly }) {
            "The link lab requires immutable source identity and listen-only proof."
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
        ) { "The link lab refuses duplicate source evidence identities." }
        val sourceByWireIdentity = ordered.associate { item ->
            (item.observation.sessionId to item.observation.sourceSequence) to item.sourceId
        }
        require(sourceByWireIdentity.size == ordered.size) {
            "A wire identity resolves to more than one immutable source."
        }

        val reports = LinkReliabilityScenario.entries.map { scenario ->
            check(shouldContinue()) { "Link reliability lab cancelled." }
            runScenario(ordered, sourceByWireIdentity, scenario, soakCycles, shouldContinue)
        }
        return LinkReliabilityMatrixReport(
            contractVersion = LINK_RELIABILITY_CONTRACT_VERSION,
            label = LINK_RELIABILITY_LABEL,
            sourceClassification = HISTORICAL_REPLAY_SOURCE,
            soakCycles = soakCycles,
            scenarios = reports,
            authority = "Offline transport recovery only; no live link, RF immunity, CAN meaning, or vehicle health is proven.",
        )
    }

    private fun runScenario(
        records: List<DiscoveryObservation>,
        sourceByWireIdentity: Map<Pair<UInt, ULong>, String>,
        scenario: LinkReliabilityScenario,
        soakCycles: Int,
        shouldContinue: () -> Boolean,
    ): LinkReliabilityScenarioReport {
        val cycles = if (scenario == LinkReliabilityScenario.CLEAN_SOAK) soakCycles else 1
        val totalUnits = Math.multiplyExact(records.size, cycles)
        var wireDeliveries = 0

        fun forEachUnit(action: (Int, WireUnit) -> Unit) {
            var outerSequence = 0UL
            repeat(cycles) {
                records.forEach { source ->
                    outerSequence++
                    action(
                        outerSequence.toInt(),
                        WireUnit(
                            GatewayFrame(
                                messageType = MessageType.RAW_CAN_FRAME,
                                sequence = outerSequence,
                                monotonicMicroseconds = outerSequence * 2_000UL,
                                payload = source.observation.encodeLive(),
                            ).encode(),
                            source,
                        ),
                    )
                }
            }
        }
        val receiver = ReliabilityReceiver(sourceByWireIdentity)
        val expected = mutableListOf<DiscoveryObservation>()
        val expectedIdentities = mutableSetOf<Triple<String, UInt, ULong>>()
        var inducedLost = 0
        var maximumStallMillis = 0L

        fun expect(source: DiscoveryObservation) {
            val identity = Triple(
                source.sourceId,
                source.observation.sessionId,
                source.observation.sourceSequence,
            )
            if (expectedIdentities.add(identity)) expected += source
        }

        if (scenario.burstFrames > 1) {
            val group = ArrayList<WireUnit>(scenario.burstFrames)
            fun deliverGroup() {
                if (group.isEmpty()) return
                check(shouldContinue()) { "Link reliability lab cancelled." }
                group.forEach { expect(it.source) }
                receiver.receive(group.fold(ByteArray(0)) { wire, item -> wire + item.wire })
                group.clear()
            }
            forEachUnit { _, unit ->
                wireDeliveries++
                group += unit
                if (group.size == scenario.burstFrames) deliverGroup()
            }
            deliverGroup()
        } else {
            forEachUnit { index, unit ->
                wireDeliveries++
                check(shouldContinue()) { "Link reliability lab cancelled." }
                val fault = faultFor(scenario, index, totalUnits)
                val fragments = fragments(unit.wire, scenario.fragmentSizes).toMutableList()
                when (fault) {
                    Fault.CLEAN -> {
                        expect(unit.source)
                        fragments.forEach(receiver::receive)
                    }
                    Fault.SHORT_STALL -> {
                        expect(unit.source)
                        maximumStallMillis = maxOf(maximumStallMillis, scenario.stallMillis)
                        fragments.forEach(receiver::receive)
                    }
                    Fault.DUPLICATE_FRAME -> {
                        expect(unit.source)
                        fragments.forEach(receiver::receive)
                        fragments.forEach(receiver::receive)
                    }
                    Fault.DUPLICATE_NOTIFICATION -> {
                        inducedLost++
                        val duplicate = minOf(maxOf(2, fragments.size / 2), fragments.lastIndex)
                        fragments.forEachIndexed { fragmentIndex, fragment ->
                            receiver.receive(fragment)
                            if (fragmentIndex == duplicate) receiver.receive(fragment)
                        }
                    }
                    Fault.NOTIFICATION_LOSS -> {
                        inducedLost++
                        val missing = minOf(maxOf(2, fragments.size / 2), fragments.lastIndex)
                        fragments.forEachIndexed { fragmentIndex, fragment ->
                            if (fragmentIndex != missing) receiver.receive(fragment)
                        }
                    }
                    Fault.PAYLOAD_CORRUPTION -> {
                        inducedLost++
                        val damaged = unit.wire.copyOf().also { bytes ->
                            val offset = minOf(GatewayFrame.HEADER_LENGTH + 4, bytes.lastIndex)
                            bytes[offset] = (bytes[offset].toInt() xor 0x80).toByte()
                        }
                        fragments(damaged, scenario.fragmentSizes).forEach(receiver::receive)
                    }
                    Fault.NOTIFICATION_REORDER -> {
                        inducedLost++
                        val left = minOf(2, fragments.size - 2)
                        val prior = fragments[left]
                        fragments[left] = fragments[left + 1]
                        fragments[left + 1] = prior
                        fragments.forEach(receiver::receive)
                    }
                    Fault.MID_FRAME_RECONNECT -> {
                        inducedLost++
                        val split = maxOf(1, fragments.size / 2)
                        fragments.take(split).forEach(receiver::receive)
                        val oldEpoch = receiver.reconnect()
                        fragments.drop(split).forEach { receiver.receive(it, oldEpoch) }
                    }
                    Fault.STALE_PRIOR_EPOCH -> {
                        expect(unit.source)
                        val oldEpoch = receiver.reconnect()
                        receiver.receive(unit.wire, oldEpoch)
                        fragments.forEach(receiver::receive)
                    }
                    Fault.SUPERVISION_TIMEOUT -> {
                        expect(unit.source)
                        maximumStallMillis = maxOf(maximumStallMillis, scenario.stallMillis)
                        receiver.reconnect()
                        fragments.forEach(receiver::receive)
                    }
                    Fault.WHOLE_FRAME_LOSS -> inducedLost++
                }
            }
        }

        val exact = receiver.accepted == expected
        val duplicateDegradation = if (scenario == LinkReliabilityScenario.CLEAN_SOAK) {
            0L
        } else {
            receiver.duplicateRejections
        }
        val degraded = listOf(
            inducedLost.toLong(),
            duplicateDegradation,
            receiver.staleEpochRejections,
            receiver.outerSequenceRegressionRejections,
            receiver.outerSequenceGaps.toLong(),
            receiver.reconnects.toLong(),
            receiver.decoder.recoveryCount,
            if (maximumStallMillis >= LINK_RELIABILITY_SUPERVISION_BUDGET_MILLIS) 1L else 0L,
        ).any { it > 0 }
        val quality = if (degraded) LinkQuality.DEGRADED else LinkQuality.HEALTHY
        val passed = exact && receiver.decoder.bufferedByteCount == 0 &&
            receiver.decoder.maximumBufferedByteCount <= LINK_RELIABILITY_MAXIMUM_BUFFER_BYTES &&
            quality == scenario.expectedQuality
        return LinkReliabilityScenarioReport(
            scenario = scenario,
            expectedQuality = scenario.expectedQuality,
            observedQuality = quality,
            cycles = cycles,
            wireDeliveries = wireDeliveries,
            uniqueInputRecords = records.size,
            expectedUniqueRecords = expected.size,
            acceptedUniqueRecords = receiver.accepted.size,
            inducedLostWireFrames = inducedLost,
            duplicateIdentityRejections = receiver.duplicateRejections,
            staleEpochNotificationRejections = receiver.staleEpochRejections,
            outerSequenceRegressionRejections = receiver.outerSequenceRegressionRejections,
            outerSequenceGaps = receiver.outerSequenceGaps,
            reconnects = receiver.reconnects,
            maximumStallMillis = maximumStallMillis,
            notificationFragments = receiver.notificationCount,
            decoderRecoveries = receiver.decoder.recoveryCount,
            decoderCorruptCandidates = receiver.decoder.corruptCandidateCount,
            decoderDiscardedBytes = receiver.decoder.discardedByteCount,
            decoderBufferedBytes = receiver.decoder.bufferedByteCount,
            decoderMaximumBufferedBytes = receiver.decoder.maximumBufferedByteCount,
            exactExpectedSurvivorOrderAndPayload = exact,
            passed = passed,
        )
    }

    private fun faultFor(
        scenario: LinkReliabilityScenario,
        index: Int,
        total: Int,
    ): Fault {
        if (index >= total) return Fault.CLEAN
        if (scenario == LinkReliabilityScenario.MIXED_INTERFERENCE) return when {
            index % 211 == 0 -> Fault.MID_FRAME_RECONNECT
            index % 113 == 0 -> Fault.NOTIFICATION_LOSS
            index % 107 == 0 -> Fault.PAYLOAD_CORRUPTION
            index % 103 == 0 -> Fault.NOTIFICATION_REORDER
            index % 101 == 0 -> Fault.DUPLICATE_FRAME
            else -> Fault.CLEAN
        }
        if (scenario.interval == 0 || index % scenario.interval != 0) return Fault.CLEAN
        return when (scenario) {
            LinkReliabilityScenario.JITTER_AND_SHORT_STALL -> Fault.SHORT_STALL
            LinkReliabilityScenario.DUPLICATE_FRAME -> Fault.DUPLICATE_FRAME
            LinkReliabilityScenario.DUPLICATE_NOTIFICATION -> Fault.DUPLICATE_NOTIFICATION
            LinkReliabilityScenario.NOTIFICATION_LOSS -> Fault.NOTIFICATION_LOSS
            LinkReliabilityScenario.PAYLOAD_CORRUPTION -> Fault.PAYLOAD_CORRUPTION
            LinkReliabilityScenario.NOTIFICATION_REORDER -> Fault.NOTIFICATION_REORDER
            LinkReliabilityScenario.MID_FRAME_RECONNECT -> Fault.MID_FRAME_RECONNECT
            LinkReliabilityScenario.STALE_PRIOR_EPOCH -> Fault.STALE_PRIOR_EPOCH
            LinkReliabilityScenario.SUPERVISION_TIMEOUT_RECOVERY -> Fault.SUPERVISION_TIMEOUT
            LinkReliabilityScenario.BOUNDED_QUEUE_OVERRUN -> Fault.WHOLE_FRAME_LOSS
            else -> Fault.CLEAN
        }
    }

    private fun fragments(wire: ByteArray, sizes: IntArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < wire.size) {
            val count = minOf(sizes[index % sizes.size], wire.size - offset)
            result += wire.copyOfRange(offset, offset + count)
            offset += count
            index++
        }
        return result
    }

    private data class WireUnit(val wire: ByteArray, val source: DiscoveryObservation)

    private enum class Fault {
        CLEAN,
        SHORT_STALL,
        DUPLICATE_FRAME,
        DUPLICATE_NOTIFICATION,
        NOTIFICATION_LOSS,
        PAYLOAD_CORRUPTION,
        NOTIFICATION_REORDER,
        MID_FRAME_RECONNECT,
        STALE_PRIOR_EPOCH,
        SUPERVISION_TIMEOUT,
        WHOLE_FRAME_LOSS,
    }

    private class ReliabilityReceiver(
        private val sourceByWireIdentity: Map<Pair<UInt, ULong>, String>,
    ) {
        val decoder = FrameStreamDecoder()
        val accepted = mutableListOf<DiscoveryObservation>()
        private val identities = mutableSetOf<Triple<String, UInt, ULong>>()
        var activeEpoch = 1L
            private set
        var duplicateRejections = 0L
            private set
        var staleEpochRejections = 0L
            private set
        var outerSequenceRegressionRejections = 0L
            private set
        var outerSequenceGaps = 0UL
            private set
        var reconnects = 0
            private set
        var notificationCount = 0L
            private set
        private var lastOuterSequence: ULong? = null

        fun receive(chunk: ByteArray, epoch: Long = activeEpoch) {
            notificationCount++
            if (epoch != activeEpoch) {
                staleEpochRejections++
                return
            }
            decoder.append(chunk).forEach { frame ->
                frame.decodeCanObservations().forEach { observation ->
                    val sourceId = checkNotNull(
                        sourceByWireIdentity[observation.sessionId to observation.sourceSequence]
                    ) { "Decoded link-lab record has no immutable source identity." }
                    val identity = Triple(sourceId, observation.sessionId, observation.sourceSequence)
                    if (!identities.add(identity)) {
                        duplicateRejections++
                        return@forEach
                    }
                    val prior = lastOuterSequence
                    if (prior != null && frame.sequence <= prior) {
                        identities.remove(identity)
                        outerSequenceRegressionRejections++
                        return@forEach
                    }
                    if (prior != null) {
                        val delta = frame.sequence - prior
                        if (delta > 1UL) outerSequenceGaps += delta - 1UL
                    }
                    lastOuterSequence = frame.sequence
                    accepted += DiscoveryObservation(sourceId, observation)
                }
            }
        }

        fun reconnect(): Long {
            val prior = activeEpoch
            decoder.resetBufferPreservingDiagnostics()
            activeEpoch++
            reconnects++
            lastOuterSequence = null
            return prior
        }
    }
}
