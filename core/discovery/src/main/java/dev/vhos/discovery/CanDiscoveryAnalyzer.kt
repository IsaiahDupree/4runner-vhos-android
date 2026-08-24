package dev.vhos.discovery

import dev.vhos.protocol.CanObservation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

data class DiscoveryObservation(
    val sourceId: String,
    val observation: CanObservation,
    val provenance: DiscoveryEvidenceProvenance,
)

/**
 * Authority is attached to every observation before it enters analysis or replay. Historical
 * evidence remains useful, but callers can never mistake an imported row for a live acquisition.
 */
enum class DiscoveryEvidenceProvenance {
    LOCAL_AUTHORIZED,
    IMPORTED_V1_HISTORY,
    RECOVERED_V2_HISTORY,
    AMBIGUOUS_LEGACY_HISTORY,
}

data class DiscoveryEvidenceProvenanceBreakdown(
    val localAuthorizedRecords: Int,
    val importedV1Records: Int,
    val recoveredV2Records: Int,
    val ambiguousLegacyRecords: Int,
) {
    val totalRecords: Int
        get() = localAuthorizedRecords + importedV1Records + recoveredV2Records + ambiguousLegacyRecords

    val representedClassifications: Int
        get() = listOf(
            localAuthorizedRecords,
            importedV1Records,
            recoveredV2Records,
            ambiguousLegacyRecords,
        ).count { it > 0 }

    companion object {
        fun from(input: List<DiscoveryObservation>) = DiscoveryEvidenceProvenanceBreakdown(
            localAuthorizedRecords = input.count {
                it.provenance == DiscoveryEvidenceProvenance.LOCAL_AUTHORIZED
            },
            importedV1Records = input.count {
                it.provenance == DiscoveryEvidenceProvenance.IMPORTED_V1_HISTORY
            },
            recoveredV2Records = input.count {
                it.provenance == DiscoveryEvidenceProvenance.RECOVERED_V2_HISTORY
            },
            ambiguousLegacyRecords = input.count {
                it.provenance == DiscoveryEvidenceProvenance.AMBIGUOUS_LEGACY_HISTORY
            },
        )
    }
}

data class CanAcquisitionSummary(
    val records: Int,
    val sources: Int,
    val sessions: Int,
    val uniqueIdentifiers: Int,
    val bitratesBps: List<Int>,
    val listenOnlyRecords: Int,
    val standardIdentifierRecords: Int,
    val extendedIdentifierRecords: Int,
    val remoteRequestRecords: Int,
    val captureDurationSeconds: Double,
    val estimatedObservedFrames: ULong,
    val estimatedObservedRateFps: Double,
    val retainedRecordRateFps: Double,
    val sequenceCoverage: Double,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
)

data class CanSessionSummary(
    val sourceId: String,
    val sessionId: UInt,
    val records: Int,
    val durationSeconds: Double,
    val firstSourceSequence: ULong,
    val lastSourceSequence: ULong,
    val sequenceSpan: ULong,
    val estimatedObservedRateFps: Double,
    val retainedRecordRateFps: Double,
    val sequenceCoverage: Double,
    val uniqueIdentifiers: Int,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
)

data class RawWordSummary(
    val minimum: Int,
    val maximum: Int,
    val mean: Double,
    val standardDeviation: Double,
)

data class ChecksumCandidate(
    val checked: Int,
    val matches: Int,
    val matchRate: Double,
    val candidate: Boolean,
)

data class IdentifierActivity(
    val identifier: UInt,
    val extended: Boolean,
    val records: Int,
    val sessions: Int,
    val dataLengths: List<Int>,
    val uniquePayloads: Int,
    val payloadChangeRate: Double,
    val dynamicBytePositions: List<Int>,
    val firstBigEndianWord: RawWordSummary?,
    val checksum: ChecksumCandidate,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
) {
    val identifierHex: String
        get() = if (extended) {
            String.format(Locale.US, "0x%08X", identifier.toLong())
        } else {
            String.format(Locale.US, "0x%03X", identifier.toInt())
        }
}

data class RawWordRelationshipCandidate(
    val leftIdentifier: UInt,
    val rightIdentifier: UInt,
    val pairedSamples: Int,
    val pearsonCorrelation: Double,
    val medianRightToLeftRatio: Double?,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
)

data class RepeatedChannelCandidate(
    val identifier: UInt,
    val bytePositions: List<Int>,
    val recordsCompared: Int,
    val minimum: Int,
    val maximum: Int,
    val maximumDisagreement: Int = 0,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
)

data class DiscoveryAnalysisLimits(
    val maximumObservations: Int = 100_000,
    val maximumEligibleIdentifiers: Int = 128,
    val maximumCorrelationPairs: Int = 4_096,
    val maximumPairedSamplesPerCorrelation: Int = 25_000,
) {
    fun validate(): DiscoveryAnalysisLimits = apply {
        require(maximumObservations in 1..100_000)
        require(maximumEligibleIdentifiers in 2..512)
        require(maximumCorrelationPairs in 1..130_816)
        require(maximumPairedSamplesPerCorrelation in 10..100_000)
    }
}

class DiscoveryAnalysisCancelledException : IllegalStateException("CAN discovery analysis was cancelled.")

data class CanDiscoveryReport(
    val contractVersion: String,
    val status: String,
    val authority: String,
    val acquisition: CanAcquisitionSummary,
    val sessions: List<CanSessionSummary>,
    val identifierActivity: List<IdentifierActivity>,
    val rawWordRelationships: List<RawWordRelationshipCandidate>,
    val repeatedChannels: List<RepeatedChannelCandidate>,
)

object CanDiscoveryAnalyzer {
    const val CONTRACT_VERSION = "1.0.0"
    const val STATUS = "DISCOVERY_CANDIDATE"
    const val PAIRING_WINDOW_MICROSECONDS = 250_000UL
    const val AUTHORITY =
        "Raw acquisition statistics only; no identifier, field, unit, scale, subsystem, or health meaning is accepted."

    fun analyze(
        input: List<DiscoveryObservation>,
        limits: DiscoveryAnalysisLimits = DiscoveryAnalysisLimits(),
        shouldContinue: () -> Boolean = { true },
    ): CanDiscoveryReport {
        limits.validate()
        require(input.isNotEmpty()) { "No persisted CAN observations are available." }
        require(input.size <= limits.maximumObservations) {
            "CAN discovery observation count exceeds the deterministic analysis bound."
        }
        ensureAnalysisContinues(shouldContinue)
        require(input.all { it.sourceId.isNotBlank() }) { "CAN source identity is required." }
        require(input.all { it.observation.listenOnly }) {
            "Every analyzed CAN observation must retain listen-only proof."
        }
        val duplicateIdentity = input.groupingBy {
            Triple(it.sourceId, it.observation.sessionId, it.observation.sourceSequence)
        }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateIdentity == null) { "Duplicate CAN observation identity is not analyzable." }
        validateSessionClockOrder(input)

        val sessions = input.groupBy { it.sourceId to it.observation.sessionId }
            .map { (key, records) -> sessionSummary(key, records) }
            .sortedWith(compareBy<CanSessionSummary> { it.sourceId }.thenBy { it.sessionId })
        val identifiers = input.groupBy { it.observation.identifier to it.observation.extended }
        val activity = identifiers.map { (key, records) -> identifierSummary(key, records) }
            .sortedWith(
                compareByDescending<IdentifierActivity> { it.dynamicBytePositions.size }
                    .thenByDescending { it.uniquePayloads }
                    .thenByDescending { it.records }
                    .thenBy { it.identifier }
            )
        val duration = sessions.sumOf { it.durationSeconds }
        val sequenceSpan = sessions.fold(0UL) { total, session ->
            checkedAdd(total, session.sequenceSpan, "Aggregate CAN sequence span overflowed.")
        }
        val observedIntervals = sessions.fold(0UL) { total, session ->
            checkedAdd(
                total,
                if (session.sequenceSpan > 0UL) session.sequenceSpan - 1UL else 0UL,
                "Aggregate CAN sequence interval count overflowed.",
            )
        }
        val acquisition = CanAcquisitionSummary(
            records = input.size,
            sources = input.map { it.sourceId }.toSet().size,
            sessions = sessions.size,
            uniqueIdentifiers = identifiers.size,
            bitratesBps = input.map { it.observation.bitrateBps }.distinct().sorted(),
            listenOnlyRecords = input.count { it.observation.listenOnly },
            standardIdentifierRecords = input.count { !it.observation.extended },
            extendedIdentifierRecords = input.count { it.observation.extended },
            remoteRequestRecords = input.count { it.observation.remoteRequest },
            captureDurationSeconds = duration,
            estimatedObservedFrames = sequenceSpan,
            estimatedObservedRateFps = if (duration > 0.0) observedIntervals.toDouble() / duration else 0.0,
            retainedRecordRateFps = if (duration > 0.0) input.size / duration else 0.0,
            sequenceCoverage = if (sequenceSpan > 0UL) input.size / sequenceSpan.toDouble() else 0.0,
            provenance = DiscoveryEvidenceProvenanceBreakdown.from(input),
        )
        return CanDiscoveryReport(
            contractVersion = CONTRACT_VERSION,
            status = STATUS,
            authority = AUTHORITY,
            acquisition = acquisition,
            sessions = sessions,
            identifierActivity = activity,
            rawWordRelationships = correlationCandidates(
                identifiers,
                sessions,
                input,
                limits,
                shouldContinue,
            ),
            repeatedChannels = repeatedChannelCandidates(identifiers, shouldContinue),
        )
    }

    private fun sessionSummary(
        key: Pair<String, UInt>,
        records: List<DiscoveryObservation>,
    ): CanSessionSummary {
        val sequences = records.map { it.observation.sourceSequence }
        val times = records.map { it.observation.monotonicMicroseconds }
        val firstSequence = sequences.min()
        val lastSequence = sequences.max()
        val span = checkedAdd(
            checkedSubtract(lastSequence, firstSequence, "CAN source sequence regressed."),
            1UL,
            "CAN source sequence span overflowed.",
        )
        val duration = checkedSubtract(
            times.max(),
            times.min(),
            "CAN monotonic clock regressed.",
        ).toDouble() / 1_000_000.0
        return CanSessionSummary(
            sourceId = key.first,
            sessionId = key.second,
            records = records.size,
            durationSeconds = duration,
            firstSourceSequence = firstSequence,
            lastSourceSequence = lastSequence,
            sequenceSpan = span,
            estimatedObservedRateFps = if (duration > 0.0) (span - 1UL).toDouble() / duration else 0.0,
            retainedRecordRateFps = if (duration > 0.0) records.size / duration else 0.0,
            sequenceCoverage = records.size / span.toDouble(),
            uniqueIdentifiers = records.map {
                it.observation.identifier to it.observation.extended
            }.toSet().size,
            provenance = DiscoveryEvidenceProvenanceBreakdown.from(records),
        )
    }

    private fun identifierSummary(
        key: Pair<UInt, Boolean>,
        records: List<DiscoveryObservation>,
    ): IdentifierActivity {
        val ordered = records.sortedWith(
            compareBy<DiscoveryObservation> { it.sourceId }
                .thenBy { it.observation.sessionId }
                .thenBy { it.observation.monotonicMicroseconds }
        )
        val minimumLength = ordered.minOf { it.observation.dataLength }
        val dynamic = (0 until minimumLength).filter { index ->
            ordered.map { byte(it.observation, index) }.distinct().size > 1
        }
        var transitions = 0
        var changes = 0
        val priorBySession = mutableMapOf<Pair<String, UInt>, List<Int>>()
        ordered.forEach { record ->
            val session = record.sourceId to record.observation.sessionId
            val payload = payload(record.observation)
            priorBySession[session]?.let { prior ->
                transitions++
                if (prior != payload) changes++
            }
            priorBySession[session] = payload
        }
        val words = ordered.mapNotNull { record ->
            if (record.observation.dataLength < 2) null
            else (byte(record.observation, 0) shl 8) or byte(record.observation, 1)
        }
        val checksumRecords = ordered.filter {
            !it.observation.extended && !it.observation.remoteRequest && it.observation.dataLength >= 1
        }
        val matches = checksumRecords.count { toyotaAdditiveChecksumMatches(it.observation) }
        val matchRate = if (checksumRecords.isEmpty()) 0.0 else matches.toDouble() / checksumRecords.size
        return IdentifierActivity(
            identifier = key.first,
            extended = key.second,
            records = ordered.size,
            sessions = ordered.map { it.sourceId to it.observation.sessionId }.toSet().size,
            dataLengths = ordered.map { it.observation.dataLength }.distinct().sorted(),
            uniquePayloads = ordered.map { payload(it.observation) }.toSet().size,
            payloadChangeRate = if (transitions == 0) 0.0 else changes.toDouble() / transitions,
            dynamicBytePositions = dynamic,
            firstBigEndianWord = numericSummary(words),
            checksum = ChecksumCandidate(
                checked = checksumRecords.size,
                matches = matches,
                matchRate = matchRate,
                candidate = checksumRecords.size >= 5 && matchRate >= 0.95,
            ),
            provenance = DiscoveryEvidenceProvenanceBreakdown.from(records),
        )
    }

    private fun numericSummary(values: List<Int>): RawWordSummary? {
        if (values.isEmpty()) return null
        val mean = values.average()
        val variance = values.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / values.size
        return RawWordSummary(
            minimum = values.min(),
            maximum = values.max(),
            mean = mean,
            standardDeviation = sqrt(variance),
        )
    }

    private fun correlationCandidates(
        identifiers: Map<Pair<UInt, Boolean>, List<DiscoveryObservation>>,
        sessions: List<CanSessionSummary>,
        input: List<DiscoveryObservation>,
        limits: DiscoveryAnalysisLimits,
        shouldContinue: () -> Boolean,
    ): List<RawWordRelationshipCandidate> {
        val eligible = identifiers.entries.filter { (key, values) ->
            !key.second && values.count { it.observation.dataLength >= 2 } >= 10 &&
                values.mapNotNull { firstWord(it.observation) }.distinct().size > 1
        }.map { it.key }.sortedBy { it.first }
        require(eligible.size <= limits.maximumEligibleIdentifiers) {
            "Eligible CAN identifier count exceeds the deterministic correlation bound."
        }
        val correlationPairCount = eligible.size.toLong() * (eligible.size.toLong() - 1L) / 2L
        require(correlationPairCount <= limits.maximumCorrelationPairs.toLong()) {
            "CAN correlation pair count exceeds the deterministic analysis bound."
        }
        val bySession = input.groupBy { it.sourceId to it.observation.sessionId }
        val sessionKeys = sessions.map { it.sourceId to it.sessionId }.distinct()
        // Build each identifier/session timeline once. Pair evaluation must never rescan the full
        // evidence list for every O(n^2) identifier combination.
        val timelines = eligible.associateWith { key ->
            sessionKeys.associateWith { sessionKey ->
                ensureAnalysisContinues(shouldContinue)
                wordTimeline(bySession[sessionKey].orEmpty(), key)
            }
        }
        val results = mutableListOf<RawWordRelationshipCandidate>()
        eligible.forEachIndexed { leftIndex, leftKey ->
            eligible.drop(leftIndex + 1).forEach { rightKey ->
                ensureAnalysisContinues(shouldContinue)
                val leftValues = mutableListOf<Double>()
                val rightValues = mutableListOf<Double>()
                sessionKeys.forEach { sessionKey ->
                    appendNearestPairs(
                        left = timelines.getValue(leftKey).getValue(sessionKey),
                        right = timelines.getValue(rightKey).getValue(sessionKey),
                        leftValues = leftValues,
                        rightValues = rightValues,
                        maximumSamples = limits.maximumPairedSamplesPerCorrelation,
                        shouldContinue = shouldContinue,
                    )
                }
                if (leftValues.size < 10) return@forEach
                val correlation = pearson(leftValues, rightValues) ?: return@forEach
                if (abs(correlation) < 0.95) return@forEach
                val ratios = leftValues.indices.mapNotNull { index ->
                    leftValues[index].takeIf { it != 0.0 }?.let { rightValues[index] / it }
                }.sorted()
                results += RawWordRelationshipCandidate(
                    leftIdentifier = leftKey.first,
                    rightIdentifier = rightKey.first,
                    pairedSamples = leftValues.size,
                    pearsonCorrelation = correlation,
                    medianRightToLeftRatio = median(ratios),
                    provenance = DiscoveryEvidenceProvenanceBreakdown.from(
                        identifiers.getValue(leftKey) + identifiers.getValue(rightKey)
                    ),
                )
            }
        }
        return results.sortedWith(
            compareByDescending<RawWordRelationshipCandidate> { abs(it.pearsonCorrelation) }
                .thenByDescending { it.pairedSamples }
                .thenBy { it.leftIdentifier }
        ).take(12)
    }

    private fun repeatedChannelCandidates(
        identifiers: Map<Pair<UInt, Boolean>, List<DiscoveryObservation>>,
        shouldContinue: () -> Boolean,
    ): List<RepeatedChannelCandidate> {
        val results = mutableListOf<RepeatedChannelCandidate>()
        identifiers.entries.sortedBy { it.key.first }.forEach { (key, records) ->
            ensureAnalysisContinues(shouldContinue)
            if (key.second || records.size < 5) return@forEach
            val length = records.minOf { it.observation.dataLength }
            val columns = mutableMapOf<List<Int>, MutableList<Int>>()
            (0 until length).forEach { index ->
                val values = records.map { byte(it.observation, index) }
                if (values.distinct().size > 1) columns.getOrPut(values) { mutableListOf() } += index
            }
            columns.forEach { (values, positions) ->
                if (positions.size >= 2) {
                    results += RepeatedChannelCandidate(
                        identifier = key.first,
                        bytePositions = positions,
                        recordsCompared = records.size,
                        minimum = values.min(),
                        maximum = values.max(),
                        provenance = DiscoveryEvidenceProvenanceBreakdown.from(records),
                    )
                }
            }
        }
        return results.sortedWith(
            compareByDescending<RepeatedChannelCandidate> { it.recordsCompared }
                .thenBy { it.identifier }
        )
    }

    private fun wordTimeline(
        records: List<DiscoveryObservation>,
        key: Pair<UInt, Boolean>,
    ): List<Pair<ULong, Double>> = records.mapNotNull { record ->
        if (record.observation.identifier != key.first || record.observation.extended != key.second) null
        else firstWord(record.observation)?.let {
            record.observation.monotonicMicroseconds to it.toDouble()
        }
    }.sortedBy { it.first }

    private fun appendNearestPairs(
        left: List<Pair<ULong, Double>>,
        right: List<Pair<ULong, Double>>,
        leftValues: MutableList<Double>,
        rightValues: MutableList<Double>,
        maximumSamples: Int,
        shouldContinue: () -> Boolean,
    ) {
        if (left.isEmpty() || right.isEmpty()) return
        var rightIndex = 0
        left.forEach { (timestamp, value) ->
            ensureAnalysisContinues(shouldContinue)
            while (rightIndex + 1 < right.size &&
                distance(right[rightIndex + 1].first, timestamp) <= distance(right[rightIndex].first, timestamp)
            ) rightIndex++
            if (distance(right[rightIndex].first, timestamp) <= PAIRING_WINDOW_MICROSECONDS) {
                require(leftValues.size < maximumSamples) {
                    "CAN correlation sample count exceeds the deterministic per-pair bound."
                }
                leftValues += value
                rightValues += right[rightIndex].second
            }
        }
    }

    private fun pearson(left: List<Double>, right: List<Double>): Double? {
        if (left.size != right.size || left.size < 2) return null
        val leftMean = left.average()
        val rightMean = right.average()
        var numerator = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        left.indices.forEach { index ->
            val leftDelta = left[index] - leftMean
            val rightDelta = right[index] - rightMean
            numerator += leftDelta * rightDelta
            leftSquares += leftDelta * leftDelta
            rightSquares += rightDelta * rightDelta
        }
        val denominator = sqrt(leftSquares * rightSquares)
        return if (denominator == 0.0) null else numerator / denominator
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle]
        else (values[middle - 1] + values[middle]) / 2.0
    }

    private fun toyotaAdditiveChecksumMatches(observation: CanObservation): Boolean {
        val payload = payload(observation)
        val expected = (((observation.identifier.toInt() shr 8) and 0xFF) +
            (observation.identifier.toInt() and 0xFF) + observation.dataLength +
            payload.dropLast(1).sum()) and 0xFF
        return expected == payload.last()
    }

    private fun firstWord(observation: CanObservation): Int? =
        if (observation.dataLength < 2) null
        else (byte(observation, 0) shl 8) or byte(observation, 1)

    private fun payload(observation: CanObservation): List<Int> =
        (0 until observation.dataLength).map { byte(observation, it) }

    private fun byte(observation: CanObservation, index: Int): Int =
        observation.data[index].toUByte().toInt()

    private fun distance(left: ULong, right: ULong): ULong =
        if (left >= right) left - right else right - left

    private fun validateSessionClockOrder(input: List<DiscoveryObservation>) {
        input.groupBy { it.sourceId to it.observation.sessionId }.values.forEach { records ->
            val orderedByTime = records.sortedWith(
                compareBy<DiscoveryObservation> { it.observation.monotonicMicroseconds }
                    .thenBy { it.observation.sourceSequence }
            )
            orderedByTime.zipWithNext().forEach { (prior, current) ->
                require(current.observation.sourceSequence > prior.observation.sourceSequence) {
                    "CAN source sequence regressed or wrapped within a capture session."
                }
            }
            val orderedBySequence = records.sortedWith(
                compareBy<DiscoveryObservation> { it.observation.sourceSequence }
                    .thenBy { it.observation.monotonicMicroseconds }
            )
            orderedBySequence.zipWithNext().forEach { (prior, current) ->
                require(
                    current.observation.monotonicMicroseconds >=
                        prior.observation.monotonicMicroseconds
                ) { "CAN monotonic clock regressed or wrapped within a capture session." }
            }
        }
    }

    private fun checkedAdd(left: ULong, right: ULong, message: String): ULong {
        require(right <= ULong.MAX_VALUE - left) { message }
        return left + right
    }

    private fun checkedSubtract(left: ULong, right: ULong, message: String): ULong {
        require(left >= right) { message }
        return left - right
    }

    private fun ensureAnalysisContinues(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw DiscoveryAnalysisCancelledException()
    }
}
