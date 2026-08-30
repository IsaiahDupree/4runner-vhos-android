package dev.vhos.headunit

import dev.vhos.maintenance.DistanceUnit
import dev.vhos.maintenance.MaintenanceActor
import dev.vhos.maintenance.MaintenanceActorSource
import dev.vhos.maintenance.MaintenanceComponentRef
import dev.vhos.maintenance.MaintenanceEventType
import dev.vhos.maintenance.MaintenanceRecordRevision
import dev.vhos.maintenance.MaintenanceRecordState
import dev.vhos.maintenance.MaintenanceSystemRef
import dev.vhos.maintenance.OdometerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceUiProjectionTest {
    @Test
    fun noteEventUsesOwnerFacingLabel() {
        assertEquals("Note / other", maintenanceEventLabel(MaintenanceEventType.OTHER))
        assertEquals("Fluid service", maintenanceEventLabel(MaintenanceEventType.FLUID_SERVICE))
    }

    @Test
    fun timelineRowPreservesUnitsAndVoidedState() {
        val row = maintenanceRecordListLabel(
            MaintenanceRecordRevision(
                recordId = id("maintenance", 1),
                revisionId = id("maintrev", 2),
                supersedesRevisionId = id("maintrev", 1),
                vehicleId = id("veh", 1),
                vehicleAssetRevisionId = id("vehrev", 2),
                state = MaintenanceRecordState.VOIDED,
                eventType = MaintenanceEventType.REPLACEMENT,
                title = "Front brake pads",
                occurredAt = "2026-08-30T12:00:00Z",
                odometer = OdometerReading(154_210, DistanceUnit.MILES),
                systems = listOf(MaintenanceSystemRef("brakes", "Brakes")),
                components = listOf(
                    MaintenanceComponentRef(
                        componentId = id("component", 1),
                        systemId = "brakes",
                        displayName = "Front brake pads",
                    ),
                ),
                actor = MaintenanceActor(MaintenanceActorSource.OWNER, "actor_00000000000000000000000003", "Owner"),
                amendmentReason = "Duplicate receipt entry",
            ).validate(),
        )

        assertTrue(row.contains("Replacement  •  Front brake pads"))
        assertTrue(row.contains("2026-08-30  •  154210 mi"))
        assertTrue(row.endsWith("VOIDED"))
    }

    private fun id(prefix: String, sequence: Int): String =
        "${prefix}_${sequence.toString().padStart(26, '0')}"
}
