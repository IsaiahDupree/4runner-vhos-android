package dev.vhos.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MaintenanceArchivePayloadTest {
    private val actor = MaintenanceActor(
        MaintenanceActorSource.OWNER,
        id("actor", 1),
        "Owner",
    ).validate()
    private val vehicle = VehicleAsset(
        vehicleId = id("veh", 1),
        revisionId = id("vehrev", 1),
        createdAt = "2026-08-30T12:00:00Z",
        displayName = "Vehicle",
        modelYear = 2020,
        make = "Example",
        model = "Car",
    ).validate()
    private val attachment = AttachmentMetadata(
        attachmentId = id("attachment", 1),
        displayName = "receipt.pdf",
        mediaType = "application/pdf",
        byteCount = 7,
        sha256 = "ab".repeat(32),
        storageKey = "sqlcipher:sha256/${"ab".repeat(32)}",
        capturedAt = "2026-08-30T12:01:00Z",
    ).validate()
    private val record = MaintenanceRecordRevision(
        recordId = id("maintenance", 1),
        revisionId = id("maintrev", 1),
        vehicleId = vehicle.vehicleId,
        vehicleAssetRevisionId = vehicle.revisionId,
        eventType = MaintenanceEventType.SERVICE,
        title = "Documented maintenance",
        occurredAt = "2026-08-30T12:01:00Z",
        systems = listOf(MaintenanceSystemRef.unknown()),
        components = listOf(MaintenanceComponentRef.unknown()),
        attachments = listOf(attachment),
        actor = actor,
        createdAt = "2026-08-30T12:02:00Z",
    ).validate()
    private val audit = MaintenanceAuditEvent(
        auditEventId = id("audit", 1),
        vehicleId = vehicle.vehicleId,
        recordId = record.recordId,
        revisionId = record.revisionId,
        priorRevisionId = null,
        action = MaintenanceAuditAction.CREATED,
        actor = actor,
        recordedAt = record.createdAt,
        reason = null,
    ).validate()

    @Test
    fun completeLedgerGraphValidatesAndEnumeratesAvailableAttachmentBodies() {
        val payload = payload().validate()

        assertEquals(MaintenanceLedgerArchivePayload.PAYLOAD_SCHEMA, "${payload.contract}@${payload.contractVersion}")
        assertEquals(setOf(attachment.sha256), payload.referencedAttachmentSha256())
    }

    @Test
    fun everyMaintenanceRevisionRequiresExactlyOneAuditReceipt() {
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(auditEvents = emptyList()).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(auditEvents = listOf(audit, audit.copy(auditEventId = id("audit", 2)))).validate()
        }
    }

    @Test
    fun archiveRejectsBranchedVehicleHistory() {
        val next = vehicle.copy(
            revisionId = id("vehrev", 2),
            supersedesRevisionId = vehicle.revisionId,
            createdAt = "2026-08-30T12:03:00Z",
        ).validate()
        val conflictingSuccessor = next.copy(
            revisionId = id("vehrev", 3),
            createdAt = "2026-08-30T12:04:00Z",
        ).validate()

        assertThrows(IllegalArgumentException::class.java) {
            MaintenanceLedgerArchivePayload(
                vehicleId = vehicle.vehicleId,
                exportedAt = "2026-08-30T13:00:00Z",
                vehicleAssetRevisions = listOf(vehicle, next, conflictingSuccessor),
                componentRevisions = emptyList(),
                requirementRevisions = emptyList(),
                recordRevisions = emptyList(),
                auditEvents = emptyList(),
            ).validate()
        }
    }

    @Test
    fun archiveRejectsMissingComponentAndCompletionLineage() {
        val unregisteredComponentRecord = record.copy(
            components = listOf(
                MaintenanceComponentRef(
                    componentId = id("component", 1),
                    displayName = "Unregistered component",
                    systemKnowledge = MaintenanceKnowledge.UNKNOWN,
                ).validate(),
            ),
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(recordRevisions = listOf(unregisteredComponentRecord)).validate()
        }

        val missingRequirementRecord = record.copy(
            completionClaims = listOf(
                MaintenanceCompletionClaim(
                    baselineId = id("maintbaseline", 1),
                    taskId = id("mainttask", 1),
                    requirementId = id("requirement", 1),
                    trust = MaintenanceCompletionTrust.OWNER_ATTESTED,
                ).validate(emptySet()),
            ),
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(recordRevisions = listOf(missingRequirementRecord)).validate()
        }
    }

    @Test
    fun archiveRejectsAuditThatDoesNotExactlyDescribeItsRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(
                auditEvents = listOf(audit.copy(recordId = id("maintenance", 2)).validate()),
            ).validate()
        }
    }

    @Test
    fun archiveRejectsSuccessorTimestampThatMovesBackwards() {
        val next = vehicle.copy(
            revisionId = id("vehrev", 2),
            supersedesRevisionId = vehicle.revisionId,
            createdAt = "2026-08-30T11:59:00Z",
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(vehicleAssetRevisions = listOf(vehicle, next)).validate()
        }
    }

    private fun payload() = MaintenanceLedgerArchivePayload(
        vehicleId = vehicle.vehicleId,
        exportedAt = "2026-08-30T13:00:00Z",
        vehicleAssetRevisions = listOf(vehicle),
        componentRevisions = emptyList(),
        requirementRevisions = emptyList(),
        recordRevisions = listOf(record),
        auditEvents = listOf(audit),
    )

    private fun id(prefix: String, sequence: Int): String =
        "${prefix}_${sequence.toString().padStart(26, '0')}"
}
