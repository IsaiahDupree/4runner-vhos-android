package dev.vhos.discovery

import dev.vhos.model.PersistedLiveCanObservation
import dev.vhos.protocol.CanObservation
import java.util.Locale

data class LiveCanEngineeringValue(
    val seriesId: String,
    val candidateId: String,
    val identifier: UInt,
    val identifierHex: String,
    val proposedSemantic: String?,
    val rawValue: Double,
    val value: Double,
    val unit: String,
    val dataLength: Int,
    val dataHex: String,
    val rawFieldFormula: String,
    val transformFormula: String,
    val transformId: String,
    val competingTransformCount: Int,
    val sourceSequence: ULong,
    val gatewayMonotonicMicroseconds: ULong,
    val receivedAtEpochMs: Long,
    val ageMillis: Long,
    val observationCount: Long,
    val payloadChangeCount: Long,
    val updateRateHz: Double?,
    val latestChangedByteIndices: List<Int>,
    val latestDataLengthChanged: Boolean,
    val requiredBadge: String,
    val authority: String,
) {
    companion object {
        const val AUTHORITY =
            "Live persisted listen-only CAN projected through a pinned cross-model hypothesis; unit, scale, semantic, and target applicability remain unverified."
    }
}

data class LiveCanRawChannel(
    val identifier: UInt,
    val identifierHex: String,
    val dataLength: Int,
    val dataHex: String,
    val sourceSequence: ULong,
    val gatewayMonotonicMicroseconds: ULong,
    val receivedAtEpochMs: Long,
    val ageMillis: Long,
    val observationCount: Long,
    val payloadChangeCount: Long,
    val updateRateHz: Double?,
    val latestChangedByteIndices: List<Int>,
    val latestDataLengthChanged: Boolean,
    /** True only when a separate pinned UNVERIFIED physical candidate is shown for this same ID. */
    val hasPinnedPhysicalProjection: Boolean,
    val authority: String,
) {
    companion object {
        const val AUTHORITY =
            "Fresh persisted listen-only raw CAN only; no physical unit, semantic, or health meaning is accepted."
    }
}

data class LiveCanEngineeringProjection(
    val contractVersion: String,
    val status: String,
    val packId: String,
    val packVersion: String,
    val packSha256: String,
    val freshnessMillis: Long,
    val totalRuntimeSamples: Int,
    val freshSamples: Int,
    val staleSamples: Int,
    val values: List<LiveCanEngineeringValue>,
    /** Exact raw inventory for every fresh identifier, including IDs that also have pinned fields. */
    val rawChannels: List<LiveCanRawChannel>,
)

/** Pure, fail-closed projection of the bounded runtime CAN snapshot. */
object LiveCanEngineeringProjector {
    const val CONTRACT_VERSION = "1.0.0"
    const val STATUS_LIVE = "LIVE_ENGINEERING_UNVERIFIED"
    const val STATUS_UNAVAILABLE = "LIVE_ENGINEERING_UNAVAILABLE"
    const val DEFAULT_FRESHNESS_MILLIS = 5_000L

    private val rawUnits = setOf("raw_count", "enum_code")

    fun project(
        input: List<PersistedLiveCanObservation>,
        pack: SignalHypothesisPack,
        nowEpochMs: Long,
        freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
    ): LiveCanEngineeringProjection {
        require(nowEpochMs > 0)
        require(freshnessMillis in 250L..60_000L)
        require(input.size <= 64) { "Live CAN runtime input exceeds its bounded contract." }
        require(input.map { Triple(it.sourceId, it.identifier, it.extended) }.distinct().size == input.size) {
            "Live CAN runtime input is not latest-per-identifier."
        }
        require(input.map { it.sourceId to it.sessionId }.distinct().size <= 1) {
            "Live CAN runtime input crosses a source or capture-session boundary."
        }

        val ages = input.associateWith { sample ->
            (nowEpochMs - sample.receivedAtEpochMs).takeIf { it >= 0L }
        }
        val fresh = input.filter { sample ->
            ages.getValue(sample)?.let { it <= freshnessMillis } == true
        }
        if (fresh.isEmpty()) {
            return LiveCanEngineeringProjection(
                contractVersion = CONTRACT_VERSION,
                status = STATUS_UNAVAILABLE,
                packId = pack.packId,
                packVersion = pack.packVersion,
                packSha256 = pack.sha256,
                freshnessMillis = freshnessMillis,
                totalRuntimeSamples = input.size,
                freshSamples = 0,
                staleSamples = input.size,
                values = emptyList(),
                rawChannels = emptyList(),
            )
        }

        val discoveryInput = fresh.map { sample ->
            DiscoveryObservation(
                sourceId = sample.sourceId,
                provenance = DiscoveryEvidenceProvenance.LOCAL_AUTHORIZED,
                observation = CanObservation(
                    sessionId = sample.sessionId,
                    sourceSequence = sample.sourceSequence,
                    monotonicMicroseconds = sample.gatewayMonotonicMicroseconds,
                    bitrateBps = sample.bitrateBps,
                    identifier = sample.identifier,
                    extended = sample.extended,
                    remoteRequest = false,
                    listenOnly = true,
                    dataLength = sample.dataLength,
                    data = sample.data.map(Int::toByte).toByteArray(),
                ),
            )
        }
        val evaluation = SignalHypothesisEvaluator.evaluate(discoveryInput, pack)
        val definitionById = pack.document.hypotheses.associateBy { it.hypothesisId }
        val sampleByKey = fresh.associateBy { it.identifier.toInt() to it.extended }
        val values = evaluation.evaluations.flatMap { candidate ->
            val definition = definitionById.getValue(candidate.hypothesisId)
            val sample = sampleByKey[definition.identifier to definition.extended]
                ?: return@flatMap emptyList()
            val rawValue = candidate.fieldValues?.mean ?: return@flatMap emptyList()
            candidate.transformEvaluations.filterNot { it.unit in rawUnits }.map { transform ->
                LiveCanEngineeringValue(
                    seriesId = "${candidate.hypothesisId}/${transform.transformId}",
                    candidateId = candidate.hypothesisId,
                    identifier = candidate.identifier,
                    identifierHex = candidate.identifierHex,
                    proposedSemantic = candidate.candidateSemantic,
                    rawValue = rawValue,
                    value = transform.summary.mean,
                    unit = transform.unit,
                    dataLength = sample.dataLength,
                    dataHex = dataHex(sample),
                    rawFieldFormula = requireNotNull(candidate.fieldFormula),
                    transformFormula = transform.formula,
                    transformId = transform.transformId,
                    competingTransformCount = candidate.transformEvaluations.size,
                    sourceSequence = sample.sourceSequence,
                    gatewayMonotonicMicroseconds = sample.gatewayMonotonicMicroseconds,
                    receivedAtEpochMs = sample.receivedAtEpochMs,
                    ageMillis = requireNotNull(ages.getValue(sample)),
                    observationCount = sample.observationCount,
                    payloadChangeCount = sample.payloadChangeCount,
                    updateRateHz = updateRateHz(sample),
                    latestChangedByteIndices = changedByteIndices(sample.latestChangedByteMask),
                    latestDataLengthChanged = sample.latestDataLengthChanged,
                    requiredBadge = evaluation.requiredBadge,
                    authority = LiveCanEngineeringValue.AUTHORITY,
                )
            }
        }.sortedWith(
            compareByDescending<LiveCanEngineeringValue> { it.payloadChangeCount > 0L }
                .thenByDescending { it.payloadChangeCount }
                .thenByDescending { it.observationCount }
                .thenBy { it.identifier }
                .thenBy { it.candidateId }
                .thenBy { it.transformId }
        )

        val physicalKeys = evaluation.evaluations.flatMap { candidate ->
            val definition = definitionById.getValue(candidate.hypothesisId)
            candidate.transformEvaluations.filterNot { it.unit in rawUnits }
                .map { definition.identifier.toUInt() to definition.extended }
        }.toSet()
        val rawChannels = fresh.map { sample ->
            LiveCanRawChannel(
                identifier = sample.identifier,
                identifierHex = identifierHex(sample.identifier, sample.extended),
                dataLength = sample.dataLength,
                dataHex = sample.data.take(sample.dataLength).joinToString(" ") {
                    String.format(Locale.US, "%02X", it)
                },
                sourceSequence = sample.sourceSequence,
                gatewayMonotonicMicroseconds = sample.gatewayMonotonicMicroseconds,
                receivedAtEpochMs = sample.receivedAtEpochMs,
                ageMillis = requireNotNull(ages.getValue(sample)),
                observationCount = sample.observationCount,
                payloadChangeCount = sample.payloadChangeCount,
                updateRateHz = updateRateHz(sample),
                latestChangedByteIndices = changedByteIndices(sample.latestChangedByteMask),
                latestDataLengthChanged = sample.latestDataLengthChanged,
                hasPinnedPhysicalProjection = (sample.identifier to sample.extended) in physicalKeys,
                authority = LiveCanRawChannel.AUTHORITY,
            )
        }.sortedWith(
            compareByDescending<LiveCanRawChannel> { it.payloadChangeCount > 0L }
                .thenByDescending { it.payloadChangeCount }
                .thenByDescending { it.observationCount }
                .thenBy { it.identifier }
        )

        return LiveCanEngineeringProjection(
            contractVersion = CONTRACT_VERSION,
            status = STATUS_LIVE,
            packId = evaluation.packId,
            packVersion = evaluation.packVersion,
            packSha256 = evaluation.packSha256,
            freshnessMillis = freshnessMillis,
            totalRuntimeSamples = input.size,
            freshSamples = fresh.size,
            staleSamples = input.size - fresh.size,
            values = values,
            rawChannels = rawChannels,
        )
    }

    private fun identifierHex(identifier: UInt, extended: Boolean): String = if (extended) {
        String.format(Locale.US, "0x%08X", identifier.toLong())
    } else {
        String.format(Locale.US, "0x%03X", identifier.toInt())
    }

    private fun dataHex(sample: PersistedLiveCanObservation): String =
        sample.data.take(sample.dataLength).joinToString(" ") {
            String.format(Locale.US, "%02X", it)
        }

    private fun updateRateHz(sample: PersistedLiveCanObservation): Double? {
        if (sample.observationCount < 2L ||
            sample.gatewayMonotonicMicroseconds <= sample.firstGatewayMonotonicMicroseconds
        ) return null
        val elapsedMicroseconds =
            sample.gatewayMonotonicMicroseconds - sample.firstGatewayMonotonicMicroseconds
        return (sample.observationCount - 1L).toDouble() * 1_000_000.0 /
            elapsedMicroseconds.toDouble()
    }

    private fun changedByteIndices(mask: Int): List<Int> =
        (0..7).filter { index -> mask and (1 shl index) != 0 }
}

data class LiveCanEngineeringValueRow(
    val title: String,
    val valueText: String,
    val payloadText: String,
    val evidenceText: String,
    val changeText: String,
    val formulaText: String,
    val badge: String,
    val authority: String,
)

data class LiveCanRawRow(
    val title: String,
    val payloadText: String,
    val evidenceText: String,
    val changeText: String,
    val authority: String,
)

data class LiveCanUnitsUiModel(
    val status: String,
    val summary: String,
    val emptyReason: String?,
    val valueRows: List<LiveCanEngineeringValueRow>,
    val rawRows: List<LiveCanRawRow>,
)

/** Text-only UI model shared by the head-unit card renderer and JVM tests. */
object LiveCanUnitsUiModelProjector {
    fun project(value: LiveCanEngineeringProjection): LiveCanUnitsUiModel {
        val emptyReason = when {
            value.totalRuntimeSamples == 0 ->
                "No persisted live RAW_CAN observations are available in this connection."
            value.freshSamples == 0 ->
                "The latest persisted live RAW_CAN observations are stale; reconnect or wait for a fresh frame."
            value.values.isEmpty() && value.rawChannels.isEmpty() ->
                "Fresh CAN evidence has no displayable pinned candidate field or raw channel."
            else -> null
        }
        return LiveCanUnitsUiModel(
            status = value.status,
            summary = "${value.freshSamples} fresh identifiers / ${value.totalRuntimeSamples} bounded identifiers • " +
                "freshness ${value.freshnessMillis} ms",
            emptyReason = emptyReason,
            valueRows = value.values.map { item ->
                LiveCanEngineeringValueRow(
                    title = "${item.proposedSemantic ?: item.candidateId} • ${item.identifierHex}",
                    valueText = "${decimal(item.value)} ${item.unit}",
                    payloadText = "DLC ${item.dataLength} • ${item.dataHex.ifBlank { "EMPTY PAYLOAD" }}",
                    evidenceText = activityText(
                        rawValue = item.rawValue,
                        sourceSequence = item.sourceSequence,
                        ageMillis = item.ageMillis,
                        observationCount = item.observationCount,
                        updateRateHz = item.updateRateHz,
                    ),
                    changeText = changeText(
                        payloadChangeCount = item.payloadChangeCount,
                        latestChangedByteIndices = item.latestChangedByteIndices,
                        latestDataLengthChanged = item.latestDataLengthChanged,
                    ),
                    formulaText = "${item.transformId} • ${item.transformFormula}",
                    badge = item.requiredBadge,
                    authority = item.authority,
                )
            },
            rawRows = value.rawChannels.map { item ->
                LiveCanRawRow(
                    title = if (item.hasPinnedPhysicalProjection) {
                        "${item.identifierHex} • RAW FRAME + PINNED UNVERIFIED CANDIDATE"
                    } else {
                        "${item.identifierHex} • RAW ONLY"
                    },
                    payloadText = "DLC ${item.dataLength} • ${item.dataHex.ifBlank { "EMPTY PAYLOAD" }}",
                    evidenceText = activityText(
                        rawValue = null,
                        sourceSequence = item.sourceSequence,
                        ageMillis = item.ageMillis,
                        observationCount = item.observationCount,
                        updateRateHz = item.updateRateHz,
                    ),
                    changeText = changeText(
                        payloadChangeCount = item.payloadChangeCount,
                        latestChangedByteIndices = item.latestChangedByteIndices,
                        latestDataLengthChanged = item.latestDataLengthChanged,
                    ),
                    authority = item.authority,
                )
            },
        )
    }

    private fun activityText(
        rawValue: Double?,
        sourceSequence: ULong,
        ageMillis: Long,
        observationCount: Long,
        updateRateHz: Double?,
    ): String = buildString {
        rawValue?.let { append("raw ${decimal(it)} • ") }
        append("seq $sourceSequence • age $ageMillis ms • app-persisted $observationCount")
        updateRateHz?.let { append(" • app-observed avg ${decimal(it)} Hz") }
    }

    private fun changeText(
        payloadChangeCount: Long,
        latestChangedByteIndices: List<Int>,
        latestDataLengthChanged: Boolean,
    ): String {
        val latest = buildList {
            if (latestDataLengthChanged) add("DLC")
            if (latestChangedByteIndices.isNotEmpty()) {
                add("bytes ${latestChangedByteIndices.joinToString(",")}")
            }
        }.ifEmpty { listOf("none") }.joinToString(" + ")
        return "payload changes $payloadChangeCount • latest Δ $latest"
    }

    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
}
