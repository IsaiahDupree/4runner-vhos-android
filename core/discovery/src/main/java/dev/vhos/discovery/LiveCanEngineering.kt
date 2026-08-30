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
    val rawFieldFormula: String,
    val transformFormula: String,
    val transformId: String,
    val competingTransformCount: Int,
    val sourceSequence: ULong,
    val gatewayMonotonicMicroseconds: ULong,
    val receivedAtEpochMs: Long,
    val ageMillis: Long,
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
    val rawOnlyChannels: List<LiveCanRawChannel>,
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
                rawOnlyChannels = emptyList(),
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
                    rawFieldFormula = requireNotNull(candidate.fieldFormula),
                    transformFormula = transform.formula,
                    transformId = transform.transformId,
                    competingTransformCount = candidate.transformEvaluations.size,
                    sourceSequence = sample.sourceSequence,
                    gatewayMonotonicMicroseconds = sample.gatewayMonotonicMicroseconds,
                    receivedAtEpochMs = sample.receivedAtEpochMs,
                    ageMillis = requireNotNull(ages.getValue(sample)),
                    requiredBadge = evaluation.requiredBadge,
                    authority = LiveCanEngineeringValue.AUTHORITY,
                )
            }
        }.sortedWith(
            compareBy<LiveCanEngineeringValue> { it.identifier }
                .thenBy { it.candidateId }
                .thenBy { it.transformId }
        )

        val physicalKeys = evaluation.evaluations.flatMap { candidate ->
            val definition = definitionById.getValue(candidate.hypothesisId)
            candidate.transformEvaluations.filterNot { it.unit in rawUnits }
                .map { definition.identifier.toUInt() to definition.extended }
        }.toSet()
        val rawOnly = fresh.filter { (it.identifier to it.extended) !in physicalKeys }.map { sample ->
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
                authority = LiveCanRawChannel.AUTHORITY,
            )
        }.sortedBy { it.identifier }

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
            rawOnlyChannels = rawOnly,
        )
    }

    private fun identifierHex(identifier: UInt, extended: Boolean): String = if (extended) {
        String.format(Locale.US, "0x%08X", identifier.toLong())
    } else {
        String.format(Locale.US, "0x%03X", identifier.toInt())
    }
}

data class LiveCanEngineeringValueRow(
    val title: String,
    val valueText: String,
    val evidenceText: String,
    val formulaText: String,
    val badge: String,
    val authority: String,
)

data class LiveCanRawRow(
    val title: String,
    val payloadText: String,
    val evidenceText: String,
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
            value.values.isEmpty() && value.rawOnlyChannels.isEmpty() ->
                "Fresh CAN evidence has no displayable pinned candidate field or raw channel."
            else -> null
        }
        return LiveCanUnitsUiModel(
            status = value.status,
            summary = "${value.freshSamples} fresh / ${value.totalRuntimeSamples} bounded samples • " +
                "freshness ${value.freshnessMillis} ms",
            emptyReason = emptyReason,
            valueRows = value.values.map { item ->
                LiveCanEngineeringValueRow(
                    title = "${item.proposedSemantic ?: item.candidateId} • ${item.identifierHex}",
                    valueText = "${decimal(item.value)} ${item.unit}",
                    evidenceText = "raw ${decimal(item.rawValue)} • seq ${item.sourceSequence} • age ${item.ageMillis} ms",
                    formulaText = "${item.transformId} • ${item.transformFormula}",
                    badge = item.requiredBadge,
                    authority = item.authority,
                )
            },
            rawRows = value.rawOnlyChannels.map { item ->
                LiveCanRawRow(
                    title = "${item.identifierHex} • RAW ONLY",
                    payloadText = "DLC ${item.dataLength} • ${item.dataHex.ifBlank { "EMPTY PAYLOAD" }}",
                    evidenceText = "seq ${item.sourceSequence} • age ${item.ageMillis} ms",
                    authority = item.authority,
                )
            },
        )
    }

    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
}
