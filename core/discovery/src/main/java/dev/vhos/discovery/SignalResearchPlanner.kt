package dev.vhos.discovery

data class SignalResearchMission(
    val rank: Int,
    val hypothesisId: String,
    val identifier: UInt,
    val identifierHex: String,
    val candidateSemantic: String?,
    val stage: String,
    val researchPriority: Int,
    val records: Int,
    val sessions: Int,
    val sourceCount: Int,
    val primaryResearchSourceCount: Int,
    val reasons: List<String>,
    val blockers: List<String>,
    val nextValidation: String,
    val ownerDisplayAllowed: Boolean,
)

data class SignalResearchBrief(
    val contractVersion: String,
    val status: String,
    val packId: String,
    val packVersion: String,
    val packSha256: String,
    val candidateCount: Int,
    val presentCount: Int,
    val dynamicFieldCount: Int,
    val independentReferenceReadyCount: Int,
    val ownerDisplayAllowed: Boolean,
    val missions: List<SignalResearchMission>,
)

object SignalResearchPlanner {
    const val CONTRACT_VERSION = "1.0.0"
    const val STATUS = "ENGINEERING_RESEARCH_PLAN"

    fun plan(
        discovery: CanDiscoveryReport,
        evaluation: SignalHypothesisEvaluationReport,
        pack: SignalHypothesisPack,
    ): SignalResearchBrief {
        require(!evaluation.promotionAllowed && evaluation.acceptedSignalDefinitions == 0) {
            "A research plan cannot consume promoted signal definitions."
        }
        require(evaluation.evaluations.all { !it.productionValueDisplayAllowed }) {
            "A research plan cannot consume owner-display-authorized values."
        }
        require(
            evaluation.packId == pack.packId &&
                evaluation.packVersion == pack.packVersion &&
                evaluation.packSha256 == pack.sha256
        ) { "Signal evaluation and research pack lineage do not match." }

        val activityByIdentifier = discovery.identifierActivity.associateBy { it.identifier }
        val relationshipIdentifiers = discovery.rawWordRelationships.flatMap {
            listOf(it.leftIdentifier, it.rightIdentifier)
        }.toSet()
        val primarySources = pack.primaryResearchSourceIds

        val unordered = evaluation.evaluations.map { candidate ->
            val activity = activityByIdentifier[candidate.identifier]
            val candidatePrimarySources = candidate.sourceIds.count(primarySources::contains)
            val relationshipPresent = candidate.identifier in relationshipIdentifiers
            val checksumPresent = activity?.checksum?.candidate == true
            val transformConflict = candidate.transformEvaluations.size > 1
            val reasons = buildList {
                when (candidate.targetEvidenceStatus) {
                    "FIELD_PRESENT_DYNAMIC" -> add(
                        "Dynamic target field appears in ${candidate.records} retained records across " +
                            "${candidate.sessions} sessions."
                    )
                    "FIELD_PRESENT_STATIC" -> add(
                        "Target field is present but static across ${candidate.records} retained records."
                    )
                    "ID_PRESENT" -> add(
                        "Identifier is present in ${candidate.records} retained records; no field is accepted."
                    )
                    else -> add("Candidate identifier is absent from the retained target evidence.")
                }
                if (candidate.sourceIds.isNotEmpty()) add(
                    "${candidate.sourceIds.size} source references include $candidatePrimarySources " +
                        "class-B primary research sources."
                )
                if (checksumPresent) add("Retained payloads pass the Toyota additive-checksum candidate gate.")
                if (relationshipPresent) add("The identifier participates in a strong raw-word relationship.")
                if (candidate.transformEvaluations.size == 1) add(
                    "One source-pinned transform is available for independent-reference testing."
                )
            }
            val blockers = buildList {
                add("Exact 2005 4Runner applicability and an independent reference are unverified.")
                when (candidate.targetEvidenceStatus) {
                    "ABSENT" -> add("No retained target frames exercise this identifier.")
                    "FIELD_PRESENT_STATIC" -> add("The captured operating states did not vary the candidate field.")
                    "ID_PRESENT" -> add("Field width, position, signedness, scale, and unit are unresolved.")
                }
                if (transformConflict) add(
                    "${candidate.transformEvaluations.size} source-pinned transforms conflict; plausibility cannot choose one."
                )
                if (candidate.candidateSemantic == null) add("No physical semantic is assigned to this identifier.")
            }
            UnrankedMission(
                candidate = candidate,
                stage = stage(candidate, transformConflict),
                priority = priority(
                    candidate = candidate,
                    primaryResearchSourceCount = candidatePrimarySources,
                    relationshipPresent = relationshipPresent,
                    checksumPresent = checksumPresent,
                    transformConflict = transformConflict,
                ),
                primaryResearchSourceCount = candidatePrimarySources,
                reasons = reasons,
                blockers = blockers,
            )
        }

        val ranked = unordered.sortedWith(
            compareByDescending<UnrankedMission> { it.priority }
                .thenByDescending { it.candidate.records }
                .thenBy { it.candidate.identifier }
                .thenBy { it.candidate.hypothesisId }
        ).mapIndexed { index, mission ->
            SignalResearchMission(
                rank = index + 1,
                hypothesisId = mission.candidate.hypothesisId,
                identifier = mission.candidate.identifier,
                identifierHex = mission.candidate.identifierHex,
                candidateSemantic = mission.candidate.candidateSemantic,
                stage = mission.stage,
                researchPriority = mission.priority,
                records = mission.candidate.records,
                sessions = mission.candidate.sessions,
                sourceCount = mission.candidate.sourceIds.size,
                primaryResearchSourceCount = mission.primaryResearchSourceCount,
                reasons = mission.reasons,
                blockers = mission.blockers,
                nextValidation = mission.candidate.requiredValidation.firstOrNull()
                    ?: "Collect a labeled target-vehicle capture with an independent reference.",
                ownerDisplayAllowed = false,
            )
        }

        return SignalResearchBrief(
            contractVersion = CONTRACT_VERSION,
            status = STATUS,
            packId = evaluation.packId,
            packVersion = evaluation.packVersion,
            packSha256 = evaluation.packSha256,
            candidateCount = ranked.size,
            presentCount = ranked.count { it.records > 0 },
            dynamicFieldCount = evaluation.evaluations.count {
                it.targetEvidenceStatus == "FIELD_PRESENT_DYNAMIC"
            },
            independentReferenceReadyCount = ranked.count {
                it.stage == "INDEPENDENT_REFERENCE_READY"
            },
            ownerDisplayAllowed = false,
            missions = ranked,
        )
    }

    private fun stage(candidate: SignalHypothesisEvaluation, transformConflict: Boolean): String = when {
        candidate.targetEvidenceStatus == "ABSENT" -> "CAPTURE_REQUIRED"
        candidate.candidateSemantic == null -> "UNMAPPED_RESEARCH"
        candidate.targetEvidenceStatus == "ID_PRESENT" -> "FIELD_DISCOVERY_REQUIRED"
        candidate.targetEvidenceStatus == "FIELD_PRESENT_STATIC" -> "CONTROLLED_EXCITATION_REQUIRED"
        transformConflict -> "REFERENCE_DISAMBIGUATION_REQUIRED"
        candidate.targetEvidenceStatus == "FIELD_PRESENT_DYNAMIC" -> "INDEPENDENT_REFERENCE_READY"
        else -> "RESEARCH_REQUIRED"
    }

    private fun priority(
        candidate: SignalHypothesisEvaluation,
        primaryResearchSourceCount: Int,
        relationshipPresent: Boolean,
        checksumPresent: Boolean,
        transformConflict: Boolean,
    ): Int {
        var score = when {
            candidate.hypothesisStatus.startsWith("HIGH_PRIORITY") -> 25
            candidate.hypothesisStatus == "CROSS_MODEL_CANDIDATE" -> 16
            candidate.hypothesisStatus == "CORROBORATED_ID_ONLY" -> 10
            else -> 2
        }
        score += when (candidate.targetEvidenceStatus) {
            "FIELD_PRESENT_DYNAMIC" -> 30
            "FIELD_PRESENT_STATIC" -> 12
            "ID_PRESENT" -> 8
            else -> 0
        }
        score += (candidate.sourceIds.distinct().size * 3).coerceAtMost(18)
        score += (primaryResearchSourceCount * 5).coerceAtMost(15)
        if (candidate.records >= 100) score += 4
        if (candidate.sessions >= 2) score += 3
        if (candidate.transformEvaluations.size == 1) score += 7
        if (relationshipPresent) score += 5
        if (checksumPresent) score += 3
        if (transformConflict) score -= 4
        if (candidate.candidateSemantic == null) score -= 10
        return score.coerceIn(0, 100)
    }

    private data class UnrankedMission(
        val candidate: SignalHypothesisEvaluation,
        val stage: String,
        val priority: Int,
        val primaryResearchSourceCount: Int,
        val reasons: List<String>,
        val blockers: List<String>,
    )
}
