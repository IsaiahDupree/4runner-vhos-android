package dev.vhos.store

import android.content.Context
import android.database.SQLException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.vhos.maintenance.AttachmentMetadata
import dev.vhos.maintenance.CustomValueType
import dev.vhos.maintenance.DistanceUnit
import dev.vhos.maintenance.LineItemType
import dev.vhos.maintenance.MaintenanceActor
import dev.vhos.maintenance.MaintenanceActorSource
import dev.vhos.maintenance.MaintenanceApplicabilitySnapshot
import dev.vhos.maintenance.MaintenanceApplicabilityStatus
import dev.vhos.maintenance.MaintenanceAuditAction
import dev.vhos.maintenance.MaintenanceComponentRef
import dev.vhos.maintenance.MaintenanceEventType
import dev.vhos.maintenance.MaintenanceInterval
import dev.vhos.maintenance.MaintenanceMeasurement
import dev.vhos.maintenance.MaintenanceProvider
import dev.vhos.maintenance.MaintenanceRecordRevision
import dev.vhos.maintenance.MaintenanceRecordState
import dev.vhos.maintenance.MaintenanceRequirementAuthority
import dev.vhos.maintenance.MaintenanceRequirementRevision
import dev.vhos.maintenance.MaintenanceSearchQuery
import dev.vhos.maintenance.MaintenanceSystemRef
import dev.vhos.maintenance.MaintenanceTask
import dev.vhos.maintenance.MeasurementValueType
import dev.vhos.maintenance.Money
import dev.vhos.maintenance.OdometerReading
import dev.vhos.maintenance.PartFluidLineItem
import dev.vhos.maintenance.TypedCustomFieldValue
import dev.vhos.maintenance.VehicleAsset
import dev.vhos.maintenance.VehicleComponentRevision
import dev.vhos.maintenance.VehicleComponentState
import dev.vhos.maintenance.VoidMaintenanceRecordRequest
import dev.vhos.maintenance.WarrantyInfo
import dev.vhos.model.DeviceRole
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

private fun id(prefix: String, sequence: Int): String =
    "${prefix}_${sequence.toString().padStart(26, '0')}"

@RunWith(AndroidJUnit4::class)
class MaintenancePersistenceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetStore() {
        EvidenceDatabase.closeForInstrumentationTests()
        context.deleteDatabase(EvidenceDatabase.DATABASE_NAME)
    }

    @After
    fun cleanUp() {
        EvidenceDatabase.closeForInstrumentationTests()
        context.deleteDatabase(EvidenceDatabase.DATABASE_NAME)
    }

    @Test
    fun vehicleAndMaintenanceHistoryIsAppendOnlySearchableAndAutomaticallyAudited() {
        val store = EvidenceDatabase.open(context)
        assertEquals(12, store.readableDatabase.version)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleAsset(SECOND_VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)

        assertEquals(
            setOf(VEHICLE.vehicleId, SECOND_VEHICLE.vehicleId),
            store.currentVehicleAssets().map { it.vehicleId }.toSet(),
        )
        assertEquals(VEHICLE, store.currentVehicleAsset(VEHICLE.vehicleId))
        assertTrue(store.currentMaintenanceRecords(SECOND_VEHICLE.vehicleId).isEmpty())

        store.createMaintenanceRecord(INITIAL_RECORD)
        assertEquals(listOf(INITIAL_RECORD), store.currentMaintenanceRecords(VEHICLE.vehicleId))
        assertEquals(
            listOf(INITIAL_RECORD),
            store.searchMaintenanceRecords(
                MaintenanceSearchQuery(
                    vehicleId = VEHICLE.vehicleId,
                    text = "fram oil filter",
                    eventTypes = setOf(MaintenanceEventType.FLUID_SERVICE),
                    systemId = ENGINE_SYSTEM.systemId,
                    componentId = ENGINE_COMPONENT.componentId,
                ),
            ),
        )

        val revisedVehicle = VEHICLE.copy(
            revisionId = VEHICLE_REVISION_TWO,
            supersedesRevisionId = VEHICLE.revisionId,
            createdAt = "2026-08-30T12:10:00Z",
            currentOdometer = OdometerReading(154_325, DistanceUnit.MILES),
        ).validate()
        store.appendVehicleAsset(revisedVehicle)
        assertEquals(
            listOf(VEHICLE, revisedVehicle),
            store.vehicleAssetHistory(VEHICLE.vehicleId),
        )

        val staleAmendment = INITIAL_RECORD.copy(
            revisionId = RECORD_REVISION_TWO,
            supersedesRevisionId = INITIAL_RECORD.revisionId,
            createdAt = "2026-08-30T12:20:00Z",
            amendmentReason = "Corrected invoice details",
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.amendMaintenanceRecord(staleAmendment)
        }

        val amendment = staleAmendment.copy(
            vehicleAssetRevisionId = revisedVehicle.revisionId,
            title = "Engine oil and filter service",
            notes = "Receipt reviewed and filter part number confirmed.",
        ).validate()
        store.amendMaintenanceRecord(amendment)
        assertEquals(amendment, store.currentMaintenanceRecords(VEHICLE.vehicleId).single())
        assertEquals(
            listOf(INITIAL_RECORD, amendment),
            store.maintenanceRecordHistory(VEHICLE.vehicleId, INITIAL_RECORD.recordId),
        )
        assertEquals(
            listOf(MaintenanceAuditAction.AMENDED, MaintenanceAuditAction.CREATED),
            store.maintenanceAuditTrail(VEHICLE.vehicleId, INITIAL_RECORD.recordId)
                .map { it.action },
        )

        val staleFork = amendment.copy(
            revisionId = RECORD_REVISION_THREE,
            supersedesRevisionId = INITIAL_RECORD.revisionId,
            createdAt = "2026-08-30T12:30:00Z",
            amendmentReason = "Attempted stale fork",
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.amendMaintenanceRecord(staleFork)
        }

        val voided = store.voidMaintenanceRecord(
            VoidMaintenanceRecordRequest(
                vehicleId = VEHICLE.vehicleId,
                recordId = INITIAL_RECORD.recordId,
                actor = OWNER,
                reason = "Duplicate invoice imported in error",
                createdAt = "2026-08-30T12:40:00Z",
            ),
        )
        assertEquals(MaintenanceRecordState.VOIDED, voided.state)
        assertTrue(store.currentMaintenanceRecords(VEHICLE.vehicleId).isEmpty())
        assertEquals(
            voided,
            store.currentMaintenanceRecords(VEHICLE.vehicleId, includeVoided = true).single(),
        )
        assertEquals(
            3,
            store.maintenanceRecordHistory(VEHICLE.vehicleId, INITIAL_RECORD.recordId).size,
        )
        assertEquals(
            listOf(
                MaintenanceAuditAction.VOIDED,
                MaintenanceAuditAction.AMENDED,
                MaintenanceAuditAction.CREATED,
            ),
            store.maintenanceAuditTrail(VEHICLE.vehicleId, INITIAL_RECORD.recordId)
                .map { it.action },
        )
        assertEquals(
            3L,
            scalar(store, "SELECT COUNT(*) FROM maintenance_record_revisions"),
        )
        assertEquals(3L, scalar(store, "SELECT COUNT(*) FROM maintenance_audit_events"))
        assertTrue(
            store.currentMaintenanceRecords(SECOND_VEHICLE.vehicleId, includeVoided = true)
                .isEmpty(),
        )
    }

    @Test
    fun odometerUnitsAndValuesCannotBeSilentlyReinterpretedOrRewound() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)

        assertThrows(IllegalArgumentException::class.java) {
            store.appendVehicleAsset(
                VEHICLE.copy(
                    revisionId = id("vehrev", 90),
                    supersedesRevisionId = VEHICLE.revisionId,
                    createdAt = "2026-08-30T12:10:00Z",
                    distanceUnit = DistanceUnit.KILOMETERS,
                    currentOdometer = OdometerReading(248_355, DistanceUnit.KILOMETERS),
                ).validate(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.appendVehicleAsset(
                VEHICLE.copy(
                    revisionId = id("vehrev", 91),
                    supersedesRevisionId = VEHICLE.revisionId,
                    createdAt = "2026-08-30T12:10:00Z",
                    currentOdometer = OdometerReading(154_320, DistanceUnit.MILES),
                ).validate(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.createMaintenanceRecord(
                INITIAL_RECORD.copy(
                    recordId = id("maintenance", 90),
                    revisionId = id("maintrev", 90),
                    odometer = OdometerReading(248_355, DistanceUnit.KILOMETERS),
                ).validate(),
            )
        }
    }

    @Test
    fun componentLifecycleRejectsParentRetirementAndRetiredComponentResurrection() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)
        val child = VehicleComponentRevision(
            vehicleId = VEHICLE.vehicleId,
            componentId = id("component", 2),
            revisionId = id("comprev", 2),
            systemId = "engine",
            systemDisplayName = "Engine",
            displayName = "Engine oil filter",
            parentComponentId = ENGINE_COMPONENT_REGISTRY.componentId,
            installedAt = "2026-08-30T12:02:30Z",
            actor = OWNER,
            createdAt = "2026-08-30T12:03:00Z",
        ).validate()
        store.appendVehicleComponent(child)

        val parentRetirement = ENGINE_COMPONENT_REGISTRY.copy(
            revisionId = id("comprev", 3),
            supersedesRevisionId = ENGINE_COMPONENT_REGISTRY.revisionId,
            state = VehicleComponentState.RETIRED,
            retiredAt = "2026-08-30T12:04:00Z",
            createdAt = "2026-08-30T12:04:00Z",
            amendmentReason = "Engine assembly removed",
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.appendVehicleComponent(parentRetirement)
        }

        val retiredChild = child.copy(
            revisionId = id("comprev", 4),
            supersedesRevisionId = child.revisionId,
            state = VehicleComponentState.RETIRED,
            retiredAt = "2026-08-30T12:05:00Z",
            createdAt = "2026-08-30T12:05:00Z",
            amendmentReason = "Filter removed",
        ).validate()
        store.appendVehicleComponent(retiredChild)
        store.appendVehicleComponent(parentRetirement)

        val resurrection = retiredChild.copy(
            revisionId = id("comprev", 5),
            supersedesRevisionId = retiredChild.revisionId,
            state = VehicleComponentState.ACTIVE,
            retiredAt = null,
            createdAt = "2026-08-30T12:06:00Z",
            amendmentReason = "Attempted identity reuse",
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.appendVehicleComponent(resurrection)
        }
    }

    @Test
    fun componentRegistryKeepsStableIdentityAndRejectsInventedRecordComponents() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)

        val amended = ENGINE_COMPONENT_REGISTRY.copy(
            revisionId = id("comprev", 2),
            supersedesRevisionId = ENGINE_COMPONENT_REGISTRY.revisionId,
            displayName = "Engine lubrication system",
            createdAt = "2026-08-30T12:10:00Z",
            amendmentReason = "Clarified component label",
        ).validate()
        store.appendVehicleComponent(amended)

        assertEquals(amended, store.currentVehicleComponent(VEHICLE.vehicleId, amended.componentId))
        assertEquals(
            listOf(ENGINE_COMPONENT_REGISTRY, amended),
            store.vehicleComponentHistory(VEHICLE.vehicleId, amended.componentId),
        )
        assertEquals(listOf(amended), store.currentVehicleComponents(VEHICLE.vehicleId))

        assertThrows(IllegalArgumentException::class.java) {
            store.createMaintenanceRecord(
                INITIAL_RECORD.copy(
                    recordId = id("maintenance", 91),
                    revisionId = id("maintrev", 91),
                    components = listOf(
                        ENGINE_COMPONENT.copy(componentId = id("component", 99)),
                    ),
                ).validate(),
            )
        }
    }

    @Test
    fun explicitUnknownSystemAndComponentPersistWithoutInventedRegistryIdentity() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        val unknownRecord = INITIAL_RECORD.copy(
            recordId = id("maintenance", 92),
            revisionId = id("maintrev", 92),
            systems = listOf(MaintenanceSystemRef.unknown()),
            components = listOf(MaintenanceComponentRef.unknown()),
        ).validate()

        store.createMaintenanceRecord(unknownRecord)

        val saved = store.currentMaintenanceRecords(VEHICLE.vehicleId).single()
        assertEquals(unknownRecord, saved)
        assertNull(saved.systems.single().systemId)
        assertNull(saved.components.single().componentId)
        assertTrue(store.currentVehicleComponents(VEHICLE.vehicleId).isEmpty())
    }

    @Test
    fun attachmentBodiesAndCompleteArchiveGraphRoundTripWithoutLosingHistory() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)
        val requirement = ownerRequirement()
        store.createOwnerMaintenanceRequirement(requirement)

        val body = "%PDF-1.7\nVHOS receipt evidence\n%%EOF".toByteArray()
        val stored = store.storeMaintenanceAttachment(
            body,
            mediaType = "application/pdf",
            createdAt = Instant.parse("2026-08-30T12:03:00Z"),
        )
        val metadata = RECEIPT.copy(
            byteCount = stored.byteCount,
            sha256 = stored.sha256,
            storageKey = "sqlcipher:sha256/${stored.sha256}",
        ).validate()
        val record = INITIAL_RECORD.copy(
            attachments = listOf(metadata),
            warranty = INITIAL_RECORD.warranty?.copy(
                documentAttachmentIds = listOf(metadata.attachmentId),
            ),
        ).validate()
        store.createMaintenanceRecord(record)

        val payload = store.maintenanceArchivePayload(
            VEHICLE.vehicleId,
            Instant.parse("2026-08-30T14:00:00Z"),
        )
        assertEquals(listOf(VEHICLE), payload.vehicleAssetRevisions)
        assertEquals(listOf(ENGINE_COMPONENT_REGISTRY), payload.componentRevisions)
        assertEquals(listOf(requirement), payload.requirementRevisions)
        assertEquals(listOf(record), payload.recordRevisions)
        assertEquals(1, payload.auditEvents.size)
        assertEquals(setOf(stored.sha256), payload.referencedAttachmentSha256())

        val archiveAttachments = store.maintenanceAttachmentsForArchive(payload)
        assertEquals(1, archiveAttachments.size)
        assertEquals(stored.sha256, archiveAttachments.single().sha256)
        assertEquals("application/pdf", archiveAttachments.single().mediaType)
        assertArrayEquals(body, archiveAttachments.single().bytes())
        assertArrayEquals(body, requireNotNull(store.maintenanceAttachment(stored.sha256)).bytes())
        assertNull(store.maintenanceAttachment("00".repeat(32)))

        val idempotent = store.storeMaintenanceAttachment(body, "application/pdf")
        assertEquals(stored.sha256, idempotent.sha256)
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM maintenance_attachment_blobs"))
        assertThrows(IllegalArgumentException::class.java) {
            store.storeMaintenanceAttachment(body, "application/octet-stream")
        }
    }

    @Test
    fun databaseRejectsDirectUpdatesAndDeletesAcrossEveryMaintenanceTruthTable() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)
        store.createOwnerMaintenanceRequirement(ownerRequirement())
        store.createMaintenanceRecord(INITIAL_RECORD)
        store.storeMaintenanceAttachment("immutable receipt".toByteArray(), "text/plain")

        assertEquals(
            MAINTENANCE_TRUTH_TABLES.size * 2L,
            scalar(
                store,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                    "AND (name LIKE '%_reject_update' OR name LIKE '%_reject_delete')",
            ),
        )
        MAINTENANCE_TRUTH_TABLES.forEach { table ->
            assertThrows(SQLException::class.java) {
                store.writableDatabase.execSQL("UPDATE $table SET rowid = rowid")
            }
            assertThrows(SQLException::class.java) {
                store.writableDatabase.execSQL(
                    "DELETE FROM $table WHERE rowid = (SELECT MIN(rowid) FROM $table)"
                )
            }
            assertTrue(scalar(store, "SELECT COUNT(*) FROM $table") > 0)
        }
    }

    @Test
    fun migrationFromV9PreservesExistingEvidenceAndMaintenanceRows() {
        val store = EvidenceDatabase.open(context)
        val source = PersistedSource(
            sourceId = "esp32-migration-proof",
            role = DeviceRole.OBD_CAN,
            bluetoothAddress = "00:11:22:33:44:55",
            identityJson = "{\"firmware\":\"migration-proof\"}",
            validatedAt = "2026-08-30T13:00:00Z",
        )
        store.upsertValidatedSource(source)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)
        store.createMaintenanceRecord(INITIAL_RECORD)
        store.writableDatabase.version = 9
        EvidenceDatabase.closeForInstrumentationTests()

        val migrated = EvidenceDatabase.open(context)
        assertEquals(12, migrated.readableDatabase.version)
        assertEquals(listOf(source), migrated.latestValidatedSources())
        assertEquals(VEHICLE, migrated.currentVehicleAsset(VEHICLE.vehicleId))
        assertEquals(
            listOf(INITIAL_RECORD),
            migrated.currentMaintenanceRecords(VEHICLE.vehicleId),
        )
        assertEquals(
            listOf(MaintenanceAuditAction.CREATED),
            migrated.maintenanceAuditTrail(VEHICLE.vehicleId).map { it.action },
        )
    }

    @Test
    fun migrationFromV11CreatesAttachmentStorageAndAllAppendOnlyTriggers() {
        val store = EvidenceDatabase.open(context)
        store.appendVehicleAsset(VEHICLE)
        store.appendVehicleComponent(ENGINE_COMPONENT_REGISTRY)
        val requirement = ownerRequirement()
        store.createOwnerMaintenanceRequirement(requirement)
        store.createMaintenanceRecord(INITIAL_RECORD)

        // Reproduce the material schema boundary of a v11 file: planning is present, while the
        // v12 attachment table and the later trigger hardening are absent. The renamed table is
        // deliberately left as inert fixture data so this test never destroys evidence.
        val triggerNames = mutableListOf<String>()
        store.writableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' " +
                "AND (name LIKE '%_reject_update' OR name LIKE '%_reject_delete')",
            emptyArray(),
        ).use { cursor -> while (cursor.moveToNext()) triggerNames += cursor.getString(0) }
        triggerNames.forEach { trigger ->
            require(trigger.matches(Regex("^[a-z0-9_]+$")))
            store.writableDatabase.execSQL("DROP" + " TRIGGER IF EXISTS $trigger")
        }
        store.writableDatabase.execSQL(
            "ALTER TABLE maintenance_attachment_blobs " +
                "RENAME TO pre_v12_maintenance_attachment_blobs"
        )
        store.writableDatabase.version = 11
        EvidenceDatabase.closeForInstrumentationTests()

        val migrated = EvidenceDatabase.open(context)
        assertEquals(12, migrated.readableDatabase.version)
        assertEquals(VEHICLE, migrated.currentVehicleAsset(VEHICLE.vehicleId))
        assertEquals(listOf(INITIAL_RECORD), migrated.currentMaintenanceRecords(VEHICLE.vehicleId))
        assertEquals(listOf(requirement), migrated.currentMaintenanceRequirements(VEHICLE.vehicleId))
        assertEquals(
            MAINTENANCE_TRUTH_TABLES.size * 2L,
            scalar(
                migrated,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                    "AND (name LIKE '%_reject_update' OR name LIKE '%_reject_delete')",
            ),
        )
        val stored = migrated.storeMaintenanceAttachment("post-migration".toByteArray(), "text/plain")
        assertArrayEquals(
            "post-migration".toByteArray(),
            requireNotNull(migrated.maintenanceAttachment(stored.sha256)).bytes(),
        )
    }

    private fun ownerRequirement() = MaintenanceRequirementRevision(
        requirementId = id("requirement", 1),
        task = MaintenanceTask(
            taskId = id("mainttask", 1),
            title = "Engine oil service",
            system = ENGINE_SYSTEM,
            components = listOf(ENGINE_COMPONENT),
        ),
        vehicleId = VEHICLE.vehicleId,
        vehicleAssetRevisionId = VEHICLE.revisionId,
        authority = MaintenanceRequirementAuthority.OWNER_CUSTOM,
        interval = MaintenanceInterval(
            distance = OdometerReading(5_000, DistanceUnit.MILES),
            months = 6,
        ),
        applicability = MaintenanceApplicabilitySnapshot(
            status = MaintenanceApplicabilityStatus.MATCHED,
            vehicleRevisionId = VEHICLE.revisionId,
            evaluatedAt = "2026-08-30T12:02:30Z",
            rationale = "Owner-defined interval applies to this vehicle revision.",
        ),
        actor = OWNER,
        createdAt = "2026-08-30T12:02:30Z",
    ).validate()

    private fun scalar(store: EvidenceDatabase, sql: String): Long =
        store.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    companion object {
        private val VEHICLE_ID = id("veh", 1)
        private val VEHICLE_REVISION_ONE = id("vehrev", 1)
        private val VEHICLE_REVISION_TWO = id("vehrev", 2)
        private val SECOND_VEHICLE_ID = id("veh", 2)
        private val SECOND_VEHICLE_REVISION = id("vehrev", 3)
        private val RECORD_ID = id("maintenance", 1)
        private val RECORD_REVISION_ONE = id("maintrev", 1)
        private val RECORD_REVISION_TWO = id("maintrev", 2)
        private val RECORD_REVISION_THREE = id("maintrev", 3)
        private val MAINTENANCE_TRUTH_TABLES = listOf(
            "vehicle_assets",
            "vehicle_component_revisions",
            "maintenance_record_revisions",
            "maintenance_record_systems",
            "maintenance_record_components",
            "maintenance_audit_events",
            "maintenance_requirement_revisions",
            "maintenance_attachment_blobs",
        )

        private val VEHICLE = VehicleAsset(
            vehicleId = VEHICLE_ID,
            revisionId = VEHICLE_REVISION_ONE,
            createdAt = "2026-08-30T12:00:00Z",
            displayName = "2005 4Runner",
            modelYear = 2005,
            make = "Toyota",
            model = "4Runner",
            trim = "Limited",
            distanceUnit = DistanceUnit.MILES,
            currentOdometer = OdometerReading(154_321, DistanceUnit.MILES),
        ).validate()

        private val SECOND_VEHICLE = VehicleAsset(
            vehicleId = SECOND_VEHICLE_ID,
            revisionId = SECOND_VEHICLE_REVISION,
            createdAt = "2026-08-30T12:01:00Z",
            displayName = "Fleet service truck",
            modelYear = 2019,
            make = "Ford",
            model = "F-150",
        ).validate()

        private val OWNER = MaintenanceActor(
            source = MaintenanceActorSource.OWNER,
            actorId = "actor_00000000000000000000000004",
            displayName = "Vehicle owner",
        ).validate()
        private val ENGINE_SYSTEM = MaintenanceSystemRef("engine", "Engine").validate()
        private val ENGINE_COMPONENT = MaintenanceComponentRef(
            componentId = id("component", 1),
            systemId = ENGINE_SYSTEM.systemId,
            displayName = "Engine lubrication",
        ).validate()
        private val ENGINE_COMPONENT_REGISTRY = VehicleComponentRevision(
            vehicleId = VEHICLE_ID,
            componentId = requireNotNull(ENGINE_COMPONENT.componentId),
            revisionId = id("comprev", 1),
            systemId = requireNotNull(ENGINE_SYSTEM.systemId),
            systemDisplayName = requireNotNull(ENGINE_SYSTEM.displayName),
            displayName = requireNotNull(ENGINE_COMPONENT.displayName),
            actor = OWNER,
            createdAt = "2026-08-30T12:02:00Z",
        ).validate()
        private val RECEIPT = AttachmentMetadata(
            attachmentId = id("attachment", 1),
            displayName = "service-receipt.pdf",
            mediaType = "application/pdf",
            byteCount = 2_048,
            sha256 = "ab".repeat(32),
            storageKey = "maintenance/2005-4runner/service-receipt.pdf",
            capturedAt = "2026-08-30T12:04:00Z",
        ).validate()

        private val INITIAL_RECORD = MaintenanceRecordRevision(
            recordId = RECORD_ID,
            revisionId = RECORD_REVISION_ONE,
            vehicleId = VEHICLE.vehicleId,
            vehicleAssetRevisionId = VEHICLE.revisionId,
            eventType = MaintenanceEventType.FLUID_SERVICE,
            title = "Oil service",
            occurredAt = "2026-08-30T11:30:00Z",
            odometer = OdometerReading(154_321, DistanceUnit.MILES),
            engineHours = "3120.5",
            systems = listOf(ENGINE_SYSTEM),
            components = listOf(ENGINE_COMPONENT),
            provider = MaintenanceProvider(
                name = "Independent Toyota Service",
                phone = "+1-555-0100",
                email = "service@example.com",
                invoiceNumber = "INV-1042",
            ),
            totalCost = Money("USD", 8_799),
            lineItems = listOf(
                PartFluidLineItem(
                    type = LineItemType.FLUID,
                    description = "Synthetic engine oil",
                    manufacturer = "Mobil",
                    specification = "SAE 5W-30",
                    quantity = "6.5",
                    quantityUnit = "qt",
                    cost = Money("USD", 5_499),
                ),
                PartFluidLineItem(
                    type = LineItemType.PART,
                    description = "Engine oil filter",
                    manufacturer = "FRAM",
                    partNumber = "XG3614",
                    quantity = "1",
                    quantityUnit = "each",
                    cost = Money("USD", 1_299),
                ),
            ),
            measurements = listOf(
                MaintenanceMeasurement(
                    name = "Drained oil condition",
                    valueType = MeasurementValueType.TEXT,
                    value = "Dark; no visible coolant or metal",
                    conditionGrade = "serviceable",
                ),
            ),
            warranty = WarrantyInfo(
                provider = "Independent Toyota Service",
                startsOn = "2026-08-30",
                expiresOn = "2026-11-30",
                terms = "Parts and workmanship",
                documentAttachmentIds = listOf(RECEIPT.attachmentId),
            ),
            notes = "Routine service with receipt retained.",
            customFields = listOf(
                TypedCustomFieldValue(
                    fieldId = "oil.analysis_requested",
                    label = "Oil analysis requested",
                    type = CustomValueType.BOOLEAN,
                    value = "false",
                ),
            ),
            attachments = listOf(RECEIPT),
            actor = OWNER,
            createdAt = "2026-08-30T12:05:00Z",
        ).validate()
    }
}
