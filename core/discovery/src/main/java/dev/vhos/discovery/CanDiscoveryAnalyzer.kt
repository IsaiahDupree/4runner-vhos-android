package dev.vhos.discovery

import dev.vhos.protocol.CanObservation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

data class DiscoveryObservation(
    val sourceId: String,
    val observation: CanObservation,
)

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
)

data class RepeatedChannelCandidate(
    val identifier: UInt,
    val bytePositions: List<Int>,
    val recordsCompared: Int,
    val minimum: Int,
    val maximum: Int,
    val maximumDisagreement: Int = 0,
)

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

    fun analyze(input: List<DiscoveryObservation>): CanDiscoveryReport {
        require(input.isNotEmpty()) { "No persisted CAN observations are available." }
        require(input.all { it.sourceId.isNotBlank() }) { "CAN source identity is required." }
        require(input.all { it.observation.listenOnly }) {
            "Every analyzed CAN observation must retain listen-only proof."
        }
        val duplicateIdentity = input.groupingBy {
            Triple(it.sourceId, it.observation.sessionId, it.observation.sourceSequence)
        }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateIdentity == null) { "Duplicate CAN observation identity is not analyzable." }

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
        val sequenceSpan = sessions.fold(0UL) { total, session -> total + session.sequenceSpan }
        val observedIntervals = sessions.fold(0UL) { total, session ->
            total + if (session.sequenceSpan > 0UL) session.sequenceSpan - 1UL else 0UL
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
        )
        return CanDiscoveryReport(
            contractVersion = CONTRACT_VERSION,
            status = STATUS,
            authority = AUTHORITY,
            acquisition = acquisition,
            sessions = sessions,
            identifierActivity = activity,
            rawWordRelationships = correlationCandidates(identifiers, sessions, input),
            repeatedChannels = repeatedChannelCandidates(identifiers),
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
        val span = lastSequence - firstSequence + 1UL
        val duration = (times.max() - times.min()).toDouble() / 1_000_000.0
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
    ): List<RawWordRelationshipCandidate> {
        val eligible = identifiers.entries.filter { (key, values) ->
            !key.second && values.count { it.observation.dataLength >= 2 } >= 10 &&
                values.mapNotNull { firstWord(it.observation) }.distinct().size > 1
        }.map { it.key }.sortedBy { it.first }
        val bySession = input.groupBy { it.sourceId to it.observation.sessionId }
        val results = mutableListOf<RawWordRelationshipCandidate>()
        eligible.forEachIndexed { leftIndex, leftKey ->
            eligible.drop(leftIndex + 1).forEach { rightKey ->
                val leftValues = mutableListOf<Double>()
                val rightValues = mutableListOf<Double>()
                sessions.forEach { session ->
                    val sessionRecords = bySession[session.sourceId to session.sessionId].orEmpty()
                    val left = wordTimeline(sessionRecords, leftKey)
                    val right = wordTimeline(sessionRecords, rightKey)
                    nearestPairs(left, right).forEach { pair ->
                        leftValues += pair.first
                        rightValues += pair.second
                    }
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
    ): List<RepeatedChannelCandidate> {
        val results = mutableListOf<RepeatedChannelCandidate>()
        identifiers.entries.sortedBy { it.key.first }.forEach { (key, records) ->
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

    private fun nearestPairs(
        left: List<Pair<ULong, Double>>,
        right: List<Pair<ULong, Double>>,
    ): List<Pair<Double, Double>> {
        if (left.isEmpty() || right.isEmpty()) return emptyList()
        var rightIndex = 0
        return buildList {
            left.forEach { (timestamp, value) ->
                while (rightIndex + 1 < right.size &&
                    distance(right[rightIndex + 1].first, timestamp) <= distance(right[rightIndex].first, timestamp)
                ) rightIndex++
                if (distance(right[rightIndex].first, timestamp) <= PAIRING_WINDOW_MICROSECONDS) {
                    add(value to right[rightIndex].second)
                }
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
}
