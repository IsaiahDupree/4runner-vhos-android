package dev.vhos.discovery

import kotlin.math.abs
import kotlin.math.sign

/**
 * Read-only projection for the engineering CAN-units dashboard.
 *
 * The projection deliberately keeps standardized J1979 values outside this model: those values
 * arrive from the current validated diagnostic contract, while every value here is calculated
 * from retained passive-CAN evidence and a hash-pinned discovery-only hypothesis pack.
 */
data class CanCandidateUnitSeries(
    val seriesId: String,
    val candidateId: String,
    val identifier: UInt,
    val identifierHex: String,
    val proposedSemantic: String?,
    val evidenceStatus: String,
    val records: Int,
    val sessions: Int,
    val rawFieldFormula: String,
    val rawFieldSummary: CandidateValueSummary,
    val transform: CandidateTransformEvaluation,
    val competingTransformCount: Int,
    val hypothesisSourceIds: List<String>,
    val limitations: String,
    val requiredBadge: String,
    val authority: String,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
) {
    fun validate(): CanCandidateUnitSeries = apply {
        require(seriesId.isNotBlank() && candidateId.isNotBlank() && identifierHex.isNotBlank())
        require(records > 0 && sessions > 0 && rawFieldSummary.count in 1..records)
        require(transform.summary.count == rawFieldSummary.count)
        require(competingTransformCount >= 1)
        require(hypothesisSourceIds.isNotEmpty() && limitations.isNotBlank())
        require(requiredBadge == SignalHypothesisEvaluator.REQUIRED_BADGE)
        require(authority == AUTHORITY)
        require(provenance.totalRecords == records)
    }

    companion object {
        const val AUTHORITY =
            "Historical engineering projection only; the unit, scale, semantic, and target applicability are unverified."
    }
}

data class CanRawOnlyChannel(
    val identifier: UInt,
    val identifierHex: String,
    val records: Int,
    val sessions: Int,
    val dynamicBytePositions: List<Int>,
    val firstBigEndianWord: RawWordSummary?,
    val checksumMatches: Int,
    val checksumChecked: Int,
    val candidateSemantics: List<String>,
    val authority: String,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
) {
    companion object {
        const val AUTHORITY =
            "Raw retained channel only; no engineering unit, scale, semantic, or health meaning is accepted."
    }
}

data class CanDerivedRelationship(
    val relationshipId: String,
    val leftCandidateId: String,
    val leftIdentifierHex: String,
    val rightCandidateId: String,
    val rightIdentifierHex: String,
    val pairedSamples: Int,
    val maximumPairingDeltaMicroseconds: ULong,
    val pearsonCorrelation: Double,
    val medianRawRightToLeftRatio: Double?,
    val commonCandidateUnit: String?,
    val medianCandidateUnitRightToLeftRatio: Double?,
    val formula: String,
    val interpretation: String,
    val authority: String,
    val provenance: DiscoveryEvidenceProvenanceBreakdown,
) {
    fun validate(): CanDerivedRelationship = apply {
        require(relationshipId.isNotBlank() && leftCandidateId.isNotBlank() && rightCandidateId.isNotBlank())
        require(pairedSamples >= 10 && maximumPairingDeltaMicroseconds > 0UL)
        require(pearsonCorrelation in -1.0..1.0)
        require(formula.isNotBlank() && interpretation.isNotBlank())
        require(authority == AUTHORITY)
        require((commonCandidateUnit == null) == (medianCandidateUnitRightToLeftRatio == null))
    }

    companion object {
        const val AUTHORITY =
            "Derived from same-source, same-session nearest pairs; correlation is not identity, causation, gear, slip, or health proof."
    }
}

data class CanUnitsDashboardProjection(
    val contractVersion: String,
    val status: String,
    val packId: String,
    val packVersion: String,
    val packSha256: String,
    val requiredBadge: String,
    val standardizedValuesAuthority: String,
    val candidateValuesAuthority: String,
    val candidateUnitSeries: List<CanCandidateUnitSeries>,
    val rawOnlyChannels: List<CanRawOnlyChannel>,
    val derivedRelationships: List<CanDerivedRelationship>,
)

object CanUnitsDashboardProjector {
    const val CONTRACT_VERSION = "1.0.0"
    const val STATUS = "ENGINEERING_HISTORICAL_UNITS"
    const val STANDARDIZED_VALUES_AUTHORITY =
        "Standardized physical values are shown separately only from a current validated SAE J1979 supported-PID response."

    private val rawUnits = setOf("raw_count", "enum_code")

    fun project(
        discovery: CanDiscoveryReport,
        evaluation: SignalHypothesisEvaluationReport,
    ): CanUnitsDashboardProjection {
        require(discovery.status == CanDiscoveryAnalyzer.STATUS)
        require(evaluation.status == SignalHypothesisEvaluator.STATUS)
        require(!evaluation.promotionAllowed && evaluation.acceptedSignalDefinitions == 0)
        require(evaluation.evaluations.all { !it.productionValueDisplayAllowed })
        require(evaluation.relationships.all { !it.productionValueDisplayAllowed })

        val evaluatedByIdentifier = evaluation.evaluations.groupBy { it.identifier }
        val physicalSeries = evaluation.evaluations.flatMap { candidate ->
            val rawSummary = candidate.fieldValues ?: return@flatMap emptyList()
            candidate.transformEvaluations.filterNot { it.unit in rawUnits }.map { transform ->
                CanCandidateUnitSeries(
                    seriesId = "${candidate.hypothesisId}/${transform.transformId}",
                    candidateId = candidate.hypothesisId,
                    identifier = candidate.identifier,
                    identifierHex = candidate.identifierHex,
                    proposedSemantic = candidate.candidateSemantic,
                    evidenceStatus = candidate.targetEvidenceStatus,
                    records = candidate.records,
                    sessions = candidate.sessions,
                    rawFieldFormula = requireNotNull(candidate.fieldFormula),
                    rawFieldSummary = rawSummary,
                    transform = transform,
                    competingTransformCount = candidate.transformEvaluations.size,
                    hypothesisSourceIds = candidate.sourceIds,
                    limitations = candidate.limitations,
                    requiredBadge = evaluation.requiredBadge,
                    authority = CanCandidateUnitSeries.AUTHORITY,
                    provenance = candidate.provenance,
                ).validate()
            }
        }.sortedWith(
            compareByDescending<CanCandidateUnitSeries> { it.records }
                .thenBy { it.identifier }
                .thenBy { it.seriesId }
        )

        val physicalIdentifiers = physicalSeries.map { it.identifier }.toSet()
        val rawOnly = discovery.identifierActivity.filter { it.identifier !in physicalIdentifiers }
            .map { activity ->
                CanRawOnlyChannel(
                    identifier = activity.identifier,
                    identifierHex = activity.identifierHex,
                    records = activity.records,
                    sessions = activity.sessions,
                    dynamicBytePositions = activity.dynamicBytePositions,
                    firstBigEndianWord = activity.firstBigEndianWord,
                    checksumMatches = activity.checksum.matches,
                    checksumChecked = activity.checksum.checked,
                    candidateSemantics = evaluatedByIdentifier[activity.identifier].orEmpty()
                        .mapNotNull { it.candidateSemantic }
                        .distinct()
                        .sorted(),
                    authority = CanRawOnlyChannel.AUTHORITY,
                    provenance = activity.provenance,
                )
            }

        return CanUnitsDashboardProjection(
            contractVersion = CONTRACT_VERSION,
            status = STATUS,
            packId = evaluation.packId,
            packVersion = evaluation.packVersion,
            packSha256 = evaluation.packSha256,
            requiredBadge = evaluation.requiredBadge,
            standardizedValuesAuthority = STANDARDIZED_VALUES_AUTHORITY,
            candidateValuesAuthority = evaluation.authority,
            candidateUnitSeries = physicalSeries,
            rawOnlyChannels = rawOnly,
            derivedRelationships = relationships(discovery, evaluation),
        )
    }

    private fun relationships(
        discovery: CanDiscoveryReport,
        evaluation: SignalHypothesisEvaluationReport,
    ): List<CanDerivedRelationship> {
        val candidateById = evaluation.evaluations.associateBy { it.hypothesisId }
        return evaluation.relationships.mapNotNull { definition ->
            if (definition.maximumPairingDeltaMicroseconds !=
                CanDiscoveryAnalyzer.PAIRING_WINDOW_MICROSECONDS
            ) return@mapNotNull null
            val left = candidateById[definition.leftHypothesisId] ?: return@mapNotNull null
            val right = candidateById[definition.rightHypothesisId] ?: return@mapNotNull null
            val leftTransform = left.transformEvaluations.firstOrNull {
                it.transformId == definition.leftTransformId
            } ?: return@mapNotNull null
            val rightTransform = right.transformEvaluations.firstOrNull {
                it.transformId == definition.rightTransformId
            } ?: return@mapNotNull null
            val raw = discovery.rawWordRelationships.firstOrNull { relationship ->
                relationship.leftIdentifier == left.identifier &&
                    relationship.rightIdentifier == right.identifier
            } ?: return@mapNotNull null
            val commonUnit = leftTransform.unit.takeIf { it == rightTransform.unit &&
                leftTransform.offset == 0.0 && rightTransform.offset == 0.0 &&
                leftTransform.scale != 0.0 && rightTransform.scale != 0.0
            }
            val candidateRatio = commonUnit?.let {
                raw.medianRightToLeftRatio?.times(rightTransform.scale / leftTransform.scale)
            }
            val candidateCorrelation = raw.pearsonCorrelation *
                sign(leftTransform.scale) * sign(rightTransform.scale)
            CanDerivedRelationship(
                relationshipId = definition.relationshipId,
                leftCandidateId = left.hypothesisId,
                leftIdentifierHex = left.identifierHex,
                rightCandidateId = right.hypothesisId,
                rightIdentifierHex = right.identifierHex,
                pairedSamples = raw.pairedSamples,
                maximumPairingDeltaMicroseconds = definition.maximumPairingDeltaMicroseconds,
                pearsonCorrelation = candidateCorrelation.coerceIn(-1.0, 1.0),
                medianRawRightToLeftRatio = raw.medianRightToLeftRatio,
                commonCandidateUnit = commonUnit,
                medianCandidateUnitRightToLeftRatio = candidateRatio,
                formula = "nearest same-session pairs <= ${definition.maximumPairingDeltaMicroseconds} us; " +
                    "right/left after ${rightTransform.formula} and ${leftTransform.formula}",
                interpretation = definition.interpretation,
                authority = CanDerivedRelationship.AUTHORITY,
                provenance = raw.provenance,
            ).validate()
        }.sortedByDescending { abs(it.pearsonCorrelation) }
    }
}
