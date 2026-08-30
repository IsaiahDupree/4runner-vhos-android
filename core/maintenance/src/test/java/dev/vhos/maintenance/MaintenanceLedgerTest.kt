package dev.vhos.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceLedgerTest {
    private val actor = MaintenanceActor(MaintenanceActorSource.OWNER, "actor_00000000000000000000000001", "Owner")
    private val vehicle = VehicleAsset(
        vehicleId = id("veh", 1),
        revisionId = id("vehrev", 2),
        createdAt = "2026-08-30T12:00:00Z",
        displayName = "Daily driver",
        modelYear = 2018,
        make = "Example",
        model = "Vehicle",
        currentOdometer = OdometerReading(90_000, DistanceUnit.MILES),
    ).validate()

    @Test
    fun genericVehicleDoesNotRequireA4RunnerPack() {
        assertEquals("Example", vehicle.make)
        assertEquals("Vehicle", vehicle.model)
    }

    @Test
    fun structuredRecordRetainsMaintenanceDataAndCustomFields() {
        val record = record().validate()

        assertEquals(MaintenanceEventType.SERVICE, record.eventType)
        assertEquals("0W-20", record.lineItems.single().specification)
        assertEquals("5.5", record.lineItems.single().quantity)
        assertEquals("mostly-highway", record.customFields.single().value)
    }

    @Test
    fun updateRequiresExplicitPredecessorAndReason() {
        val original = record().validate()
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(
                revisionId = id("maintrev", 99),
                supersedesRevisionId = original.revisionId,
                amendmentReason = null,
            ).validate()
        }
    }

    @Test
    fun deleteIsRepresentedByAVoidedRevision() {
        val original = record().validate()
        val voided = original.copy(
            revisionId = id("maintrev", 99),
            supersedesRevisionId = original.revisionId,
            state = MaintenanceRecordState.VOIDED,
            amendmentReason = "Duplicate entry",
        ).validate()
        assertEquals(MaintenanceRecordState.VOIDED, voided.state)
    }

    @Test
    fun typedCustomValuesRejectInvalidRepresentations() {
        assertThrows(IllegalArgumentException::class.java) {
            TypedCustomFieldValue(
                fieldId = "inspection.passed",
                label = "Passed",
                type = CustomValueType.BOOLEAN,
                value = "yes",
            ).validate()
        }
    }

    @Test
    fun generatedMaintenanceIdentitiesAreTypedUniqueAndMonotonic() {
        val first = MaintenanceIds.maintenanceRevision()
        val second = MaintenanceIds.maintenanceRevision()

        MaintenanceIds.requireMaintenanceRevision(first)
        MaintenanceIds.requireMaintenanceRevision(second)
        assertTrue(first < second)
        assertThrows(IllegalArgumentException::class.java) {
            MaintenanceIds.requireVehicle(first)
        }
    }

    @Test
    fun componentCannotRetireBeforeItsInstallation() {
        assertThrows(IllegalArgumentException::class.java) {
            VehicleComponentRevision(
                vehicleId = vehicle.vehicleId,
                componentId = id("component", 40),
                revisionId = id("comprev", 41),
                supersedesRevisionId = id("comprev", 40),
                systemId = "brakes",
                systemDisplayName = "Brakes",
                displayName = "Front brake pads",
                state = VehicleComponentState.RETIRED,
                installedAt = "2026-08-30T12:00:00Z",
                retiredAt = "2026-08-29T12:00:00Z",
                actor = actor,
                amendmentReason = "Removed for replacement",
            ).validate()
        }
    }

    @Test
    fun explicitUnknownSystemAndComponentRemainTruthfulEvidence() {
        val unknown = record().copy(
            systems = listOf(MaintenanceSystemRef.unknown()),
            components = listOf(MaintenanceComponentRef.unknown()),
        ).validate()

        assertEquals(MaintenanceKnowledge.UNKNOWN, unknown.systems.single().knowledge)
        assertEquals(MaintenanceKnowledge.UNKNOWN, unknown.components.single().componentKnowledge)
        assertEquals(null, unknown.components.single().componentId)
        assertThrows(IllegalArgumentException::class.java) {
            unknown.copy(
                components = listOf(
                    MaintenanceComponentRef(
                        componentId = id("component", 90),
                        displayName = "Invented component",
                        componentKnowledge = MaintenanceKnowledge.UNKNOWN,
                        systemKnowledge = MaintenanceKnowledge.UNKNOWN,
                    ),
                ),
            ).validate()
        }
    }

    @Test
    fun occurrencePrecisionRejectsInverseRepresentations() {
        assertEquals(
            "2026-08-30",
            record().copy(
                occurredAt = "2026-08-30",
                occurrencePrecision = MaintenanceOccurrencePrecision.DATE_ONLY,
            ).validate().occurredAt,
        )
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(
                occurredAt = "2026-08-30T12:00:00Z",
                occurrencePrecision = MaintenanceOccurrencePrecision.DATE_ONLY,
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(
                occurredAt = "2026-08-30",
                occurrencePrecision = MaintenanceOccurrencePrecision.INSTANT,
            ).validate()
        }
    }

    private fun record() = MaintenanceRecordRevision(
        recordId = id("maintenance", 10),
        revisionId = id("maintrev", 11),
        vehicleId = vehicle.vehicleId,
        vehicleAssetRevisionId = vehicle.revisionId,
        eventType = MaintenanceEventType.SERVICE,
        title = "Engine oil and filter",
        occurredAt = "2026-08-30T12:00:00Z",
        odometer = OdometerReading(90_000, DistanceUnit.MILES),
        systems = listOf(MaintenanceSystemRef("engine.lubrication", "Engine lubrication")),
        components = listOf(
            MaintenanceComponentRef(
                componentId = id("component", 20),
                systemId = "engine.lubrication",
                displayName = "Engine oil",
            )
        ),
        totalCost = Money("USD", 4_999),
        lineItems = listOf(
            PartFluidLineItem(
                lineItemId = id("lineitem", 30),
                type = LineItemType.FLUID,
                description = "Synthetic engine oil",
                specification = "0W-20",
                quantity = "5.5",
                quantityUnit = "qt",
            )
        ),
        customFields = listOf(
            TypedCustomFieldValue(
                fieldId = "usage.profile",
                label = "Usage profile",
                type = CustomValueType.CHOICE,
                value = "mostly-highway",
            )
        ),
        actor = actor,
        createdAt = "2026-08-30T12:05:00Z",
    )

    private fun id(prefix: String, sequence: Int): String =
        "${prefix}_${sequence.toString().padStart(26, '0')}"
}
