package dev.vhos.maintenance

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

enum class MaintenanceRequirementAuthority { OWNER_CUSTOM, VERIFIED_VEHICLE_PACK }
enum class MaintenanceRequirementState { ACTIVE, VOIDED }
enum class MaintenanceApplicabilityStatus { MATCHED, UNRESOLVED, NOT_APPLICABLE }

data class MaintenanceSourceLocator(
    val sourceDocumentId: String,
    val locatorKey: String,
    val sectionLabel: String,
    val printedPageStart: Int? = null,
    val printedPageEnd: Int? = null,
    val pdfPageStart: Int,
    val pdfPageEnd: Int,
) {
    fun validate(): MaintenanceSourceLocator = apply {
        MaintenanceIds.requireSourceDocument(sourceDocumentId)
        requirePlanKey(locatorKey, "locator_key")
        requirePlanText(sectionLabel, "section_label", 1, 300)
        printedPageStart?.let { require(it >= 1) }
        printedPageEnd?.let { end ->
            require(end >= (printedPageStart ?: 1)) { "Printed source page range is reversed." }
        }
        require(pdfPageStart >= 1 && pdfPageEnd >= pdfPageStart) { "PDF source page range is invalid." }
    }
}

data class VerifiedMaintenanceRuleRef(
    val ruleId: String,
    val packId: String,
    val packVersion: String,
    val sourceManifestId: String,
    val sourceManifestSha256: String,
    val sourceLocator: MaintenanceSourceLocator,
) {
    fun validate(): VerifiedMaintenanceRuleRef = apply {
        MaintenanceIds.requireMaintenanceRule(ruleId)
        requirePlanKey(packId, "pack_id")
        requireSemanticVersion(packVersion, "pack_version")
        MaintenanceIds.requireMaintenanceSourceManifest(sourceManifestId)
        require(sourceManifestSha256.matches(Regex("^[0-9a-f]{64}$")))
        sourceLocator.validate()
    }
}

data class MaintenanceTask(
    val taskId: String = MaintenanceIds.maintenanceTask(),
    val title: String,
    val system: MaintenanceSystemRef,
    val components: List<MaintenanceComponentRef> = emptyList(),
) {
    fun validate(): MaintenanceTask = apply {
        MaintenanceIds.requireMaintenanceTask(taskId)
        requirePlanText(title, "task title", 1, 300)
        system.validate()
        require(components.size <= 100)
        components.forEach { it.validate() }
        require(components.mapNotNull { it.componentId }.distinct().size == components.mapNotNull { it.componentId }.size)
        require(components.count { it.componentKnowledge == MaintenanceKnowledge.UNKNOWN } <= 1)
        require(components.all { it.systemKnowledge == system.knowledge && it.systemId == system.systemId }) {
            "Every task component must belong to its task system."
        }
    }
}

/** Every populated dimension is independently due; the most urgent dimension wins. */
data class MaintenanceInterval(
    val distance: OdometerReading? = null,
    val months: Int? = null,
    val engineHours: String? = null,
    val upcomingDistance: OdometerReading? = null,
    val upcomingMonths: Int? = null,
    val upcomingEngineHours: String? = null,
) {
    fun validate(): MaintenanceInterval = apply {
        require(distance != null || months != null || engineHours != null) {
            "A maintenance requirement needs at least one interval dimension."
        }
        distance?.validate()?.also { require(it.value > 0) }
        months?.let { require(it in 1..1200) }
        engineHours?.let { require(positiveDecimal(it, "engine_hours") > BigDecimal.ZERO) }
        upcomingDistance?.validate()?.let { window ->
            val interval = requireNotNull(distance) { "An upcoming distance window needs a distance interval." }
            require(window.unit == interval.unit && window.value in 1 until interval.value)
        }
        upcomingMonths?.let { window ->
            require(window in 1 until requireNotNull(months))
        }
        upcomingEngineHours?.let { value ->
            val window = positiveDecimal(value, "upcoming_engine_hours")
            require(window > BigDecimal.ZERO && window < positiveDecimal(requireNotNull(engineHours), "engine_hours"))
        }
    }
}

data class MaintenanceApplicabilitySnapshot(
    val status: MaintenanceApplicabilityStatus,
    val vehicleRevisionId: String,
    val evaluatedAt: String,
    val requiredConfigurationKeys: List<String> = emptyList(),
    val unresolvedKeys: List<String> = emptyList(),
    val rationale: String,
) {
    fun validate(): MaintenanceApplicabilitySnapshot = apply {
        MaintenanceIds.requireVehicleRevision(vehicleRevisionId)
        requirePlanInstant(evaluatedAt, "applicability evaluated_at")
        require(requiredConfigurationKeys.size <= 300 && unresolvedKeys.size <= 300)
        requiredConfigurationKeys.forEach { requirePlanKey(it, "required configuration key") }
        unresolvedKeys.forEach { requirePlanKey(it, "unresolved configuration key") }
        require(requiredConfigurationKeys.distinct().size == requiredConfigurationKeys.size)
        require(unresolvedKeys.distinct().size == unresolvedKeys.size)
        require(unresolvedKeys.all { it in requiredConfigurationKeys })
        if (status == MaintenanceApplicabilityStatus.UNRESOLVED) {
            require(unresolvedKeys.isNotEmpty()) { "Unresolved applicability must name unresolved inputs." }
        } else {
            require(unresolvedKeys.isEmpty()) { "Resolved applicability cannot retain unresolved inputs." }
        }
        requirePlanText(rationale, "applicability rationale", 1, 2000)
    }
}

data class MaintenanceRequirementRevision(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    /** Unique identity for this immutable revision. */
    val requirementId: String = MaintenanceIds.requirement(),
    val supersedesRequirementId: String? = null,
    /** Stable logical task identity retained across requirement revisions. */
    val task: MaintenanceTask,
    val vehicleId: String,
    val vehicleAssetRevisionId: String,
    val authority: MaintenanceRequirementAuthority,
    val state: MaintenanceRequirementState = MaintenanceRequirementState.ACTIVE,
    val interval: MaintenanceInterval,
    val applicability: MaintenanceApplicabilitySnapshot,
    val verifiedRule: VerifiedMaintenanceRuleRef? = null,
    val notes: String? = null,
    val actor: MaintenanceActor,
    val createdAt: String = Instant.now().toString(),
    val amendmentReason: String? = null,
) {
    fun validate(): MaintenanceRequirementRevision = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        MaintenanceIds.requireRequirement(requirementId)
        supersedesRequirementId?.let {
            MaintenanceIds.requireRequirement(it, "supersedes_requirement_id")
            require(it != requirementId)
        }
        task.validate()
        MaintenanceIds.requireVehicle(vehicleId)
        MaintenanceIds.requireVehicleRevision(vehicleAssetRevisionId)
        interval.validate()
        applicability.validate()
        require(applicability.vehicleRevisionId == vehicleAssetRevisionId) {
            "Requirement applicability must bind to its vehicle-asset revision."
        }
        when (authority) {
            MaintenanceRequirementAuthority.OWNER_CUSTOM -> require(verifiedRule == null) {
                "Owner-custom requirements cannot impersonate a verified Vehicle Pack rule."
            }
            MaintenanceRequirementAuthority.VERIFIED_VEHICLE_PACK -> requireNotNull(verifiedRule).validate()
        }
        notes?.let { requirePlanText(it, "requirement notes", 1, 10_000) }
        actor.validate()
        requirePlanInstant(createdAt, "created_at")
        if (supersedesRequirementId == null) {
            require(state == MaintenanceRequirementState.ACTIVE)
            require(amendmentReason == null)
        } else {
            require(!amendmentReason.isNullOrBlank())
            requirePlanText(requireNotNull(amendmentReason), "amendment_reason", 1, 2000)
        }
        if (state == MaintenanceRequirementState.VOIDED) require(supersedesRequirementId != null)
    }

    companion object {
        const val CONTRACT = "vehicle.maintenance-requirement"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

enum class MaintenanceCompletionTrust { OWNER_ATTESTED, DOCUMENTED, VERIFIED_IMPORT }

/** An explicit claim; a record title or component name can never satisfy a requirement. */
data class MaintenanceCompletionClaim(
    val baselineId: String = MaintenanceIds.maintenanceBaseline(),
    val taskId: String,
    val requirementId: String,
    val ruleId: String? = null,
    val packId: String? = null,
    val packVersion: String? = null,
    val trust: MaintenanceCompletionTrust,
    val evidenceAttachmentIds: List<String> = emptyList(),
) {
    fun validate(availableAttachmentIds: Set<String>): MaintenanceCompletionClaim = apply {
        MaintenanceIds.requireMaintenanceBaseline(baselineId)
        MaintenanceIds.requireMaintenanceTask(taskId)
        MaintenanceIds.requireRequirement(requirementId)
        val verifiedValues = listOf(ruleId, packId, packVersion)
        require(verifiedValues.all { it == null } || verifiedValues.all { it != null }) {
            "Rule, pack, and pack version completion lineage must be all present or all absent."
        }
        ruleId?.let(MaintenanceIds::requireMaintenanceRule)
        packId?.let { requirePlanKey(it, "completion pack_id") }
        packVersion?.let { requireSemanticVersion(it, "completion pack_version") }
        require(evidenceAttachmentIds.distinct().size == evidenceAttachmentIds.size)
        evidenceAttachmentIds.forEach {
            MaintenanceIds.requireAttachment(it)
            require(it in availableAttachmentIds) { "Completion evidence must reference an attachment on the record." }
        }
        if (trust != MaintenanceCompletionTrust.OWNER_ATTESTED) {
            require(evidenceAttachmentIds.isNotEmpty()) {
                "Documented or imported completion claims require record-bound evidence."
            }
        }
    }
}

enum class MaintenanceDueState { UNKNOWN, NOT_APPLICABLE, CURRENT, UPCOMING, DUE, OVERDUE }

data class MaintenanceDueProjection(
    val dueId: String = MaintenanceIds.maintenanceDue(),
    val requirementId: String,
    val taskId: String,
    val state: MaintenanceDueState,
    val evaluatedAt: String,
    val vehicleRevisionId: String,
    val baselineRecordId: String? = null,
    val baselineRevisionId: String? = null,
    val baselineId: String? = null,
    val targetOdometer: OdometerReading? = null,
    val targetAt: String? = null,
    val targetEngineHours: String? = null,
    val explanation: String,
) {
    fun validate(): MaintenanceDueProjection = apply {
        MaintenanceIds.requireMaintenanceDue(dueId)
        MaintenanceIds.requireRequirement(requirementId)
        MaintenanceIds.requireMaintenanceTask(taskId)
        MaintenanceIds.requireVehicleRevision(vehicleRevisionId)
        baselineRecordId?.let(MaintenanceIds::requireMaintenanceRecord)
        baselineRevisionId?.let(MaintenanceIds::requireMaintenanceRevision)
        baselineId?.let(MaintenanceIds::requireMaintenanceBaseline)
        requirePlanInstant(evaluatedAt, "due evaluated_at")
        targetOdometer?.validate()
        targetAt?.let { requirePlanInstant(it, "target_at") }
        targetEngineHours?.let { positiveDecimal(it, "target_engine_hours") }
        requirePlanText(explanation, "due explanation", 1, 4000)
    }
}

object MaintenanceDueProjector {
    fun project(
        requirement: MaintenanceRequirementRevision,
        vehicle: VehicleAsset,
        records: List<MaintenanceRecordRevision>,
        evaluatedAt: Instant = Instant.now(),
        currentEngineHours: String? = null,
    ): MaintenanceDueProjection {
        requirement.validate()
        vehicle.validate()
        fun result(
            state: MaintenanceDueState,
            explanation: String,
            record: MaintenanceRecordRevision? = null,
            claim: MaintenanceCompletionClaim? = null,
            targetOdometer: OdometerReading? = null,
            targetAt: String? = null,
            targetEngineHours: String? = null,
        ) = MaintenanceDueProjection(
            requirementId = requirement.requirementId,
            taskId = requirement.task.taskId,
            state = state,
            evaluatedAt = evaluatedAt.toString(),
            vehicleRevisionId = vehicle.revisionId,
            baselineRecordId = record?.recordId,
            baselineRevisionId = record?.revisionId,
            baselineId = claim?.baselineId,
            targetOdometer = targetOdometer,
            targetAt = targetAt,
            targetEngineHours = targetEngineHours,
            explanation = explanation,
        ).validate()

        if (requirement.state != MaintenanceRequirementState.ACTIVE) {
            return result(MaintenanceDueState.UNKNOWN, "Requirement is voided and cannot produce a due claim.")
        }
        if (requirement.vehicleId != vehicle.vehicleId ||
            requirement.applicability.vehicleRevisionId != vehicle.revisionId
        ) {
            return result(
                MaintenanceDueState.UNKNOWN,
                "Applicability was not evaluated against the current vehicle configuration revision.",
            )
        }
        when (requirement.applicability.status) {
            MaintenanceApplicabilityStatus.UNRESOLVED -> return result(
                MaintenanceDueState.UNKNOWN,
                "Applicability is unresolved: ${requirement.applicability.unresolvedKeys.joinToString()}.",
            )
            MaintenanceApplicabilityStatus.NOT_APPLICABLE -> return result(
                MaintenanceDueState.NOT_APPLICABLE,
                requirement.applicability.rationale,
            )
            MaintenanceApplicabilityStatus.MATCHED -> Unit
        }

        val candidates = records.asSequence()
            .filter { it.vehicleId == vehicle.vehicleId && it.state == MaintenanceRecordState.ACTIVE }
            .flatMap { record -> record.completionClaims.asSequence().map { record to it } }
            .filter { (_, claim) -> claim.taskId == requirement.task.taskId }
            .filter { (_, claim) ->
                val rule = requirement.verifiedRule
                rule == null || (claim.ruleId == rule.ruleId && claim.packId == rule.packId &&
                    claim.packVersion == rule.packVersion)
            }
            .filter { (_, claim) ->
                requirement.authority == MaintenanceRequirementAuthority.OWNER_CUSTOM ||
                    claim.trust != MaintenanceCompletionTrust.OWNER_ATTESTED
            }
            .sortedByDescending { (record, _) -> record.occurrenceInstant() }
            .toList()
        val (baselineRecord, baselineClaim) = candidates.firstOrNull() ?: return result(
            MaintenanceDueState.UNKNOWN,
            "No trusted explicit completion baseline exists for this task and rule version.",
        )

        val interval = requirement.interval
        var overall = MaintenanceDueState.CURRENT
        val explanations = mutableListOf<String>()
        var targetOdometer: OdometerReading? = null
        var targetAt: String? = null
        var targetEngine: String? = null

        interval.distance?.let { distance ->
            val current = vehicle.currentOdometer ?: return result(
                MaintenanceDueState.UNKNOWN,
                "Current odometer is unknown; distance due state cannot be calculated.",
                baselineRecord, baselineClaim,
            )
            val baseline = baselineRecord.odometer ?: return result(
                MaintenanceDueState.UNKNOWN,
                "Completion baseline has no odometer; distance due state cannot be calculated.",
                baselineRecord, baselineClaim,
            )
            if (current.unit != distance.unit || baseline.unit != distance.unit) return result(
                MaintenanceDueState.UNKNOWN,
                "Distance units do not agree across requirement, baseline, and vehicle.",
                baselineRecord, baselineClaim,
            )
            targetOdometer = OdometerReading(baseline.value + distance.value, distance.unit).validate()
            val value = current.value
            overall = maxUrgency(overall, when {
                value > targetOdometer!!.value -> MaintenanceDueState.OVERDUE
                value == targetOdometer!!.value -> MaintenanceDueState.DUE
                interval.upcomingDistance != null &&
                    targetOdometer!!.value - value <= interval.upcomingDistance.value -> MaintenanceDueState.UPCOMING
                else -> MaintenanceDueState.CURRENT
            })
            explanations += "Distance target is ${targetOdometer!!.value} ${distance.unit.name.lowercase()}."
        }
        interval.months?.let { months ->
            val target = baselineRecord.occurrenceInstant().atZone(ZoneOffset.UTC)
                .plusMonths(months.toLong()).toInstant()
            targetAt = target.toString()
            overall = maxUrgency(overall, when {
                evaluatedAt.isAfter(target) -> MaintenanceDueState.OVERDUE
                evaluatedAt == target -> MaintenanceDueState.DUE
                interval.upcomingMonths != null &&
                    !evaluatedAt.isBefore(target.atZone(ZoneOffset.UTC)
                        .minusMonths(interval.upcomingMonths.toLong()).toInstant()) -> MaintenanceDueState.UPCOMING
                else -> MaintenanceDueState.CURRENT
            })
            explanations += "Calendar target is $targetAt."
        }
        interval.engineHours?.let { hours ->
            val current = currentEngineHours?.let { positiveDecimal(it, "current_engine_hours") }
                ?: return result(
                    MaintenanceDueState.UNKNOWN,
                    "Current engine hours are unknown; engine-hour due state cannot be calculated.",
                    baselineRecord, baselineClaim, targetOdometer, targetAt,
                )
            val baseline = baselineRecord.engineHours?.let { positiveDecimal(it, "baseline engine_hours") }
                ?: return result(
                    MaintenanceDueState.UNKNOWN,
                    "Completion baseline has no engine hours; engine-hour due state cannot be calculated.",
                    baselineRecord, baselineClaim, targetOdometer, targetAt,
                )
            val target = baseline + positiveDecimal(hours, "engine_hours")
            targetEngine = target.stripTrailingZeros().toPlainString()
            val upcoming = interval.upcomingEngineHours?.let {
                positiveDecimal(it, "upcoming_engine_hours")
            }
            overall = maxUrgency(overall, when {
                current > target -> MaintenanceDueState.OVERDUE
                current == target -> MaintenanceDueState.DUE
                upcoming != null && target - current <= upcoming -> MaintenanceDueState.UPCOMING
                else -> MaintenanceDueState.CURRENT
            })
            explanations += "Engine-hour target is $targetEngine h."
        }
        return result(
            overall,
            "Explicit baseline ${baselineClaim.baselineId}; ${explanations.joinToString(" ")}",
            baselineRecord,
            baselineClaim,
            targetOdometer,
            targetAt,
            targetEngine,
        )
    }

    private fun maxUrgency(left: MaintenanceDueState, right: MaintenanceDueState): MaintenanceDueState {
        val order = listOf(
            MaintenanceDueState.CURRENT,
            MaintenanceDueState.UPCOMING,
            MaintenanceDueState.DUE,
            MaintenanceDueState.OVERDUE,
        )
        return if (order.indexOf(right) > order.indexOf(left)) right else left
    }
}

private fun requirePlanKey(value: String, field: String) {
    require(value.matches(Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")) && value.length <= 160) {
        "$field is not a canonical key."
    }
}

private fun requirePlanText(value: String, field: String, minimum: Int, maximum: Int) {
    require(value == value.trim() && value.length in minimum..maximum) { "$field is invalid." }
}

private fun requirePlanInstant(value: String, field: String): Instant = try {
    Instant.parse(value)
} catch (error: RuntimeException) {
    throw IllegalArgumentException("$field must be an ISO-8601 instant.", error)
}

private fun requireSemanticVersion(value: String, field: String) {
    require(value.matches(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$"))) {
        "$field must be a semantic version."
    }
}

private fun positiveDecimal(value: String, field: String): BigDecimal = try {
    BigDecimal(value).also {
        require(it.toString() == value && it >= BigDecimal.ZERO) { "$field is not a canonical non-negative decimal." }
    }
} catch (error: NumberFormatException) {
    throw IllegalArgumentException("$field is not a decimal.", error)
}
