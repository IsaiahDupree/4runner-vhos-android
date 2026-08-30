package dev.vhos.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MaintenancePlanningTest {
    private val vehicle = VehicleAsset(
        vehicleId = id("veh", 1),
        revisionId = id("vehrev", 2),
        createdAt = "2026-01-01T00:00:00Z",
        displayName = "Vehicle",
        modelYear = 2020,
        make = "Example",
        model = "Car",
        currentOdometer = OdometerReading(14_000, DistanceUnit.MILES),
    ).validate()
    private val task = MaintenanceTask(
        taskId = id("mainttask", 3),
        title = "Engine oil service",
        system = MaintenanceSystemRef("engine.lubrication", "Engine lubrication"),
    ).validate()
    private val actor = MaintenanceActor(MaintenanceActorSource.OWNER, "actor_00000000000000000000000002", "Owner")

    @Test
    fun dueStateRemainsUnknownWithoutExplicitBaselineEvenWhenTitleMatches() {
        val record = record(completionClaims = emptyList(), title = task.title)

        val projection = MaintenanceDueProjector.project(
            ownerRequirement(), vehicle, listOf(record), Instant.parse("2026-06-01T00:00:00Z"),
        )

        assertEquals(MaintenanceDueState.UNKNOWN, projection.state)
        assertTrue(projection.explanation.contains("No trusted explicit completion baseline"))
    }

    @Test
    fun explicitOwnerBaselineProducesTruthfulDistanceProjection() {
        val requirement = ownerRequirement()
        val claim = MaintenanceCompletionClaim(
            baselineId = id("maintbaseline", 7),
            taskId = task.taskId,
            requirementId = requirement.requirementId,
            trust = MaintenanceCompletionTrust.OWNER_ATTESTED,
        ).validate(emptySet())
        val projection = MaintenanceDueProjector.project(
            requirement,
            vehicle,
            listOf(record(completionClaims = listOf(claim))),
            Instant.parse("2026-06-01T00:00:00Z"),
        )

        assertEquals(MaintenanceDueState.UPCOMING, projection.state)
        assertEquals(15_000L, projection.targetOdometer?.value)
        assertEquals(claim.baselineId, projection.baselineId)
    }

    @Test
    fun unresolvedApplicabilityAndMissingEngineHoursStayUnknown() {
        val unresolved = ownerRequirement().copy(
            applicability = applicability().copy(
                status = MaintenanceApplicabilityStatus.UNRESOLVED,
                requiredConfigurationKeys = listOf("powertrain.engine"),
                unresolvedKeys = listOf("powertrain.engine"),
                rationale = "Engine configuration is unknown.",
            ),
        ).validate()
        assertEquals(
            MaintenanceDueState.UNKNOWN,
            MaintenanceDueProjector.project(unresolved, vehicle, emptyList()).state,
        )

        val hours = ownerRequirement().copy(
            interval = MaintenanceInterval(engineHours = "100", upcomingEngineHours = "10"),
        ).validate()
        val claim = MaintenanceCompletionClaim(
            taskId = task.taskId,
            requirementId = hours.requirementId,
            trust = MaintenanceCompletionTrust.OWNER_ATTESTED,
        )
        val baseline = record(completionClaims = listOf(claim), engineHours = "500")
        assertEquals(
            MaintenanceDueState.UNKNOWN,
            MaintenanceDueProjector.project(hours, vehicle, listOf(baseline), currentEngineHours = null).state,
        )
    }

    @Test
    fun verifiedPackDoesNotAcceptOwnerAttestationAsTrustedBaseline() {
        val source = MaintenanceSourceLocator(
            sourceDocumentId = id("source", 20),
            locatorKey = "maintenance.normal",
            sectionLabel = "Normal maintenance",
            pdfPageStart = 10,
            pdfPageEnd = 11,
        )
        val requirement = ownerRequirement().copy(
            authority = MaintenanceRequirementAuthority.VERIFIED_VEHICLE_PACK,
            verifiedRule = VerifiedMaintenanceRuleRef(
                ruleId = id("maintrule", 21),
                packId = "example.vehicle-pack",
                packVersion = "1.0.0",
                sourceManifestId = id("maintsource", 22),
                sourceManifestSha256 = "a".repeat(64),
                sourceLocator = source,
            ),
        ).validate()
        val rule = requireNotNull(requirement.verifiedRule)
        val claim = MaintenanceCompletionClaim(
            taskId = task.taskId,
            requirementId = requirement.requirementId,
            ruleId = rule.ruleId,
            packId = rule.packId,
            packVersion = rule.packVersion,
            trust = MaintenanceCompletionTrust.OWNER_ATTESTED,
        )

        assertEquals(
            MaintenanceDueState.UNKNOWN,
            MaintenanceDueProjector.project(
                requirement,
                vehicle,
                listOf(record(completionClaims = listOf(claim))),
            ).state,
        )
    }

    private fun ownerRequirement() = MaintenanceRequirementRevision(
        requirementId = id("requirement", 4),
        task = task,
        vehicleId = vehicle.vehicleId,
        vehicleAssetRevisionId = vehicle.revisionId,
        authority = MaintenanceRequirementAuthority.OWNER_CUSTOM,
        interval = MaintenanceInterval(
            distance = OdometerReading(5_000, DistanceUnit.MILES),
            upcomingDistance = OdometerReading(1_000, DistanceUnit.MILES),
        ),
        applicability = applicability(),
        actor = actor,
        createdAt = "2026-01-01T00:00:00Z",
    ).validate()

    private fun applicability() = MaintenanceApplicabilitySnapshot(
        status = MaintenanceApplicabilityStatus.MATCHED,
        vehicleRevisionId = vehicle.revisionId,
        evaluatedAt = "2026-01-01T00:00:00Z",
        rationale = "Owner-defined interval applies to this vehicle revision.",
    )

    private fun record(
        completionClaims: List<MaintenanceCompletionClaim>,
        title: String = "Completed service",
        engineHours: String? = null,
    ) = MaintenanceRecordRevision(
        recordId = id("maintenance", 5),
        revisionId = id("maintrev", 6),
        vehicleId = vehicle.vehicleId,
        vehicleAssetRevisionId = vehicle.revisionId,
        eventType = MaintenanceEventType.SERVICE,
        title = title,
        occurredAt = "2026-02-01T00:00:00Z",
        odometer = OdometerReading(10_000, DistanceUnit.MILES),
        engineHours = engineHours,
        systems = listOf(task.system),
        components = listOf(
            MaintenanceComponentRef(id("component", 8), task.system.systemId, "Engine oil")
        ),
        completionClaims = completionClaims,
        actor = actor,
        createdAt = "2026-02-01T00:01:00Z",
    ).validate()

    private fun id(prefix: String, sequence: Int): String =
        "${prefix}_${sequence.toString().padStart(26, '0')}"
}
