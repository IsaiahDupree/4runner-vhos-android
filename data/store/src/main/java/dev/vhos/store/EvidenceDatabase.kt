package dev.vhos.store

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.vhos.discovery.AndroidDiscoveryCaptureDraft
import dev.vhos.discovery.AndroidCaptureDraftState
import dev.vhos.discovery.AndroidCaptureFinalizationAuthority
import dev.vhos.discovery.AndroidDiscoveryEvidenceAnchor
import dev.vhos.discovery.AndroidDiscoveryMarkerDefinition
import dev.vhos.discovery.AndroidDiscoveryMarkerKind
import dev.vhos.discovery.AndroidDiscoverySafetyEvidence
import dev.vhos.discovery.AndroidDiscoveryMarkerRecord
import dev.vhos.discovery.AndroidDiscoveryMutationAuthority
import dev.vhos.discovery.AndroidDiscoveryEngineeringSafetyGate
import dev.vhos.discovery.AndroidDiscoveryPassiveBootstrapPolicy
import dev.vhos.discovery.AndroidDiscoverySafetyAuthorization
import dev.vhos.discovery.AndroidDiscoveryTestTemplate
import dev.vhos.model.VehicleMotion
import dev.vhos.discovery.AndroidVehicleCapabilityObservation
import dev.vhos.digitaltwin.DigitalTwinSnapshot
import dev.vhos.digitaltwin.HeadUnitInventory
import dev.vhos.digitaltwin.HealthAssessment
import dev.vhos.digitaltwin.VehicleProfile
import dev.vhos.digitaltwin.VehicleSystem
import dev.vhos.model.DeviceRole
import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.decodeCanObservations
import dev.vhos.sync.EvidenceBundles
import dev.vhos.sync.ImportedEvidenceBundle
import dev.vhos.sync.PortableEvidenceRecord
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class EvidenceCounts(
    val logicalFrames: Long,
    val canObservations: Long,
)

data class PersistedSource(
    val sourceId: String,
    val role: DeviceRole,
    val bluetoothAddress: String,
    val identityJson: String,
    val validatedAt: String,
)

data class PersistedCanObservation(
    val vehicleScopeId: String,
    val vehicleProfileRevisionId: String,
    val sourceId: String,
    val observation: CanObservation,
)

data class DiscoveryEvidenceSummary(
    val canObservations: Long,
    val canCaptureSessions: Long,
    val uniqueCanIdentifiers: Int,
    val firstIngestedAt: String?,
    val lastIngestedAt: String?,
)

data class DiscoveryEvidenceScope(
    val vehicleScopeId: String,
    val vehicleProfileRevisionId: String,
    val sourceId: String,
) {
    fun validate(): DiscoveryEvidenceScope = apply {
        require(vehicleScopeId.isNotBlank() && vehicleProfileRevisionId.isNotBlank() && sourceId.isNotBlank()) {
            "Vehicle scope, profile revision, and source are required for persisted evidence."
        }
    }

    internal fun queryArguments(): Array<String> =
        arrayOf(vehicleScopeId, vehicleProfileRevisionId, sourceId)
}

data class PersistedAndroidDiscoveryCapture(
    val session: AndroidDiscoveryCaptureDraft,
    val eventMarkerCount: Int,
)

/** String-backed extension keeps unsigned gateway lineage exact across SQLite/Gson boundaries. */
private data class StoredDiscoveryAuthorizationV1(
    val schemaVersion: Int,
    val mutationAuthority: String,
    val healthVehicleMotion: String,
    val captureSessionId: String?,
    val rawCanSourceId: String?,
    val rawCanSessionId: String?,
    val rawCanSourceSequence: String?,
    val rawCanGatewayMonotonicMicroseconds: String?,
    val rawCanReceivedAtEpochMillis: Long?,
    val listenOnlyProven: Boolean,
    val captureActiveProven: Boolean?,
    val requiredCapability: String?,
) {
    fun toAuthorization(base: AndroidDiscoverySafetyAuthorization): AndroidDiscoverySafetyAuthorization {
        require(schemaVersion == 1) { "Unsupported Discovery authorization extension $schemaVersion." }
        val rawParts = listOf(
            rawCanSourceId,
            rawCanSessionId,
            rawCanSourceSequence,
            rawCanGatewayMonotonicMicroseconds,
        )
        require(rawParts.all { it == null } || rawParts.all { it != null }) {
            "Partial live RAW_CAN authorization lineage is invalid."
        }
        val rawAnchor = rawCanSourceId?.let {
            AndroidDiscoveryEvidenceAnchor(
                sourceId = it,
                canSessionId = requireNotNull(rawCanSessionId).toUInt(),
                sourceSequence = requireNotNull(rawCanSourceSequence).toULong(),
                gatewayMonotonicMicroseconds =
                    requireNotNull(rawCanGatewayMonotonicMicroseconds).toULong(),
            ).validate()
        }
        return base.copy(
            mutationAuthority = AndroidDiscoveryMutationAuthority.valueOf(mutationAuthority),
            healthVehicleMotion = VehicleMotion.valueOf(healthVehicleMotion),
            captureSessionId = captureSessionId?.toUInt(),
            rawCanAnchor = rawAnchor,
            rawCanReceivedAtEpochMillis = rawCanReceivedAtEpochMillis,
            listenOnlyProven = listenOnlyProven,
            captureActiveProven = captureActiveProven,
            requiredCapability = requiredCapability,
        ).validate()
    }

    companion object {
        fun from(value: AndroidDiscoverySafetyAuthorization) = StoredDiscoveryAuthorizationV1(
            schemaVersion = 1,
            mutationAuthority = value.mutationAuthority.name,
            healthVehicleMotion = value.healthVehicleMotion.name,
            captureSessionId = value.captureSessionId?.toString(),
            rawCanSourceId = value.rawCanAnchor?.sourceId,
            rawCanSessionId = value.rawCanAnchor?.canSessionId?.toString(),
            rawCanSourceSequence = value.rawCanAnchor?.sourceSequence?.toString(),
            rawCanGatewayMonotonicMicroseconds =
                value.rawCanAnchor?.gatewayMonotonicMicroseconds?.toString(),
            rawCanReceivedAtEpochMillis = value.rawCanReceivedAtEpochMillis,
            listenOnlyProven = value.listenOnlyProven,
            captureActiveProven = value.captureActiveProven,
            requiredCapability = value.requiredCapability,
        )
    }
}

class EvidenceDatabase private constructor(
    context: Context,
    private val databasePassphrase: ByteArray,
    migrationState: EvidenceStoreMigrationState,
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    databasePassphrase,
    null,
    DATABASE_VERSION,
    0,
    null,
    null,
    true,
) {
    lateinit var securityStatus: EvidenceStoreSecurity
        private set

    private val initialMigrationState = migrationState

    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE sources (
              source_id TEXT PRIMARY KEY NOT NULL,
              role TEXT NOT NULL,
              bluetooth_address TEXT NOT NULL,
              identity_json TEXT NOT NULL,
              validated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        createScopedEvidenceTables(database)
        createDigitalTwinTables(database)
        createDiscoveryTables(database)
    }

    private fun createScopedEvidenceTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logical_frames (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              vehicle_scope_id TEXT NOT NULL,
              vehicle_profile_revision_id TEXT NOT NULL,
              source_id TEXT NOT NULL,
              source_role TEXT NOT NULL,
              source_sequence TEXT NOT NULL,
              source_monotonic_us TEXT NOT NULL,
              protocol_major INTEGER NOT NULL,
              protocol_minor INTEGER NOT NULL,
              message_type INTEGER NOT NULL,
              flags INTEGER NOT NULL,
              envelope BLOB NOT NULL,
              envelope_sha256 TEXT NOT NULL,
              ingested_at TEXT NOT NULL,
              FOREIGN KEY(source_id) REFERENCES sources(source_id),
              UNIQUE(vehicle_scope_id, vehicle_profile_revision_id, source_id, source_sequence, message_type, envelope_sha256)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS can_observations (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              vehicle_scope_id TEXT NOT NULL,
              vehicle_profile_revision_id TEXT NOT NULL,
              source_id TEXT NOT NULL,
              session_id TEXT NOT NULL,
              source_sequence TEXT NOT NULL,
              source_monotonic_us TEXT NOT NULL,
              bitrate_bps INTEGER NOT NULL,
              identifier INTEGER NOT NULL,
              extended INTEGER NOT NULL,
              remote_request INTEGER NOT NULL,
              listen_only INTEGER NOT NULL,
              data_length INTEGER NOT NULL,
              data BLOB NOT NULL,
              ingested_at TEXT NOT NULL,
              FOREIGN KEY(source_id) REFERENCES sources(source_id),
              UNIQUE(vehicle_scope_id, vehicle_profile_revision_id, source_id, session_id, source_sequence)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_receipts (
              bundle_id TEXT NOT NULL,
              manifest_sha256 TEXT NOT NULL,
              vehicle_scope_id TEXT NOT NULL,
              vehicle_profile_revision_id TEXT NOT NULL,
              imported_at TEXT NOT NULL,
              record_count INTEGER NOT NULL,
              PRIMARY KEY(bundle_id, manifest_sha256, vehicle_scope_id, vehicle_profile_revision_id)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS logical_frames_scope_ingested ON " +
                "logical_frames(vehicle_scope_id, vehicle_profile_revision_id, source_id, ingested_at)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS can_observations_scope_sequence ON " +
                "can_observations(vehicle_scope_id, vehicle_profile_revision_id, source_id, source_sequence)"
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var migratedVersion = oldVersion
        if (migratedVersion == 1) {
            createDigitalTwinTables(database)
            migratedVersion = 2
        }
        if (migratedVersion == 2) {
            createDiscoveryTables(database)
            migratedVersion = 3
        }
        if (migratedVersion == 3) {
            quarantineUnscopedDiscoveryV3(database)
            createDiscoveryTables(database)
            migratedVersion = 4
        }
        if (migratedVersion == 4) {
            quarantineUnscopedEvidenceV4(database)
            createScopedEvidenceTables(database)
            createDiscoveryTables(database)
            migratedVersion = 5
        }
        if (migratedVersion == 5) {
            database.execSQL(
                "ALTER TABLE discovery_capture_sessions ADD COLUMN safety_authorization_extension_json TEXT"
            )
            database.execSQL(
                "ALTER TABLE discovery_capture_sessions ADD COLUMN finalization_authorization_extension_json TEXT"
            )
            database.execSQL(
                "ALTER TABLE discovery_event_markers ADD COLUMN safety_authorization_extension_json TEXT"
            )
            migratedVersion = 6
        }
        check(migratedVersion == newVersion) {
            "Evidence database migration $oldVersion -> $newVersion is not implemented; destructive migration is forbidden."
        }
    }

    private fun createDigitalTwinTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS head_unit_inventory (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              inventory_id TEXT NOT NULL UNIQUE,
              inventory_fingerprint TEXT NOT NULL UNIQUE,
              inventory_json TEXT NOT NULL,
              captured_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vehicle_profiles (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              revision_id TEXT NOT NULL UNIQUE,
              supersedes_revision_id TEXT,
              profile_json TEXT NOT NULL,
              created_at TEXT NOT NULL,
              FOREIGN KEY(supersedes_revision_id) REFERENCES vehicle_profiles(revision_id)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS health_assessments (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              assessment_id TEXT NOT NULL UNIQUE,
              supersedes_assessment_id TEXT,
              profile_revision_id TEXT,
              system_id TEXT NOT NULL,
              health_state TEXT NOT NULL,
              evidence_basis TEXT NOT NULL,
              assessment_json TEXT NOT NULL,
              recorded_at TEXT NOT NULL,
              FOREIGN KEY(supersedes_assessment_id) REFERENCES health_assessments(assessment_id),
              FOREIGN KEY(profile_revision_id) REFERENCES vehicle_profiles(revision_id)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS head_unit_inventory_captured ON head_unit_inventory(captured_at)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS vehicle_profiles_created ON vehicle_profiles(created_at)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS health_assessments_system ON health_assessments(system_id, id)"
        )
    }

    private fun createDiscoveryTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS discovery_capture_sessions (
              session_id TEXT PRIMARY KEY NOT NULL,
              vehicle_scope_id TEXT NOT NULL,
              vehicle_profile_revision_id TEXT NOT NULL,
              capture_source_id TEXT NOT NULL,
              test_template_id TEXT NOT NULL,
              test_template_version TEXT NOT NULL,
              test_template_snapshot_json TEXT NOT NULL,
              test_template_snapshot_sha256 TEXT NOT NULL,
              state TEXT NOT NULL,
              started_at TEXT NOT NULL,
              started_elapsed_realtime_nanos TEXT NOT NULL,
              started_boot_id TEXT NOT NULL,
              ended_at TEXT,
              ended_elapsed_realtime_nanos TEXT,
              ended_boot_id TEXT,
              start_source_id TEXT,
              start_can_session_id TEXT,
              start_source_sequence TEXT,
              start_gateway_monotonic_us TEXT,
              end_source_id TEXT,
              end_can_session_id TEXT,
              end_source_sequence TEXT,
              end_gateway_monotonic_us TEXT,
              start_logical_frame_count INTEGER NOT NULL,
              start_can_observation_count INTEGER NOT NULL,
              end_logical_frame_count INTEGER,
              end_can_observation_count INTEGER,
              safety_evidence TEXT NOT NULL,
              safety_authorization_source_id TEXT NOT NULL,
              safety_authorization_frame_sequence TEXT NOT NULL,
              safety_authorization_gateway_monotonic_us TEXT NOT NULL,
              safety_authorization_received_at_ms INTEGER NOT NULL,
              finalization_authority TEXT,
              finalization_authorization_source_id TEXT,
              finalization_authorization_frame_sequence TEXT,
              finalization_authorization_gateway_monotonic_us TEXT,
              finalization_authorization_received_at_ms INTEGER,
              safety_authorization_extension_json TEXT,
              finalization_authorization_extension_json TEXT
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS discovery_event_markers (
              marker_id TEXT PRIMARY KEY NOT NULL,
              capture_session_id TEXT NOT NULL,
              event_type TEXT NOT NULL,
              label TEXT NOT NULL,
              marker_kind TEXT NOT NULL,
              value_text TEXT,
              unit TEXT,
              observed_at TEXT NOT NULL,
              elapsed_realtime_nanos TEXT NOT NULL,
              source_id TEXT,
              can_session_id TEXT,
              nearest_source_sequence TEXT,
              nearest_gateway_monotonic_us TEXT,
              observer TEXT NOT NULL,
              note TEXT,
              safety_authorization_source_id TEXT NOT NULL,
              safety_authorization_frame_sequence TEXT NOT NULL,
              safety_authorization_gateway_monotonic_us TEXT NOT NULL,
              safety_authorization_received_at_ms INTEGER NOT NULL,
              safety_authorization_extension_json TEXT,
              FOREIGN KEY(capture_session_id) REFERENCES discovery_capture_sessions(session_id)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS android_capability_observations (
              snapshot_id TEXT PRIMARY KEY NOT NULL,
              snapshot_fingerprint TEXT NOT NULL UNIQUE,
              snapshot_json TEXT NOT NULL,
              captured_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS one_active_discovery_capture " +
                "ON discovery_capture_sessions(state) WHERE state = 'ACTIVE'"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS discovery_capture_started " +
                "ON discovery_capture_sessions(" +
                "vehicle_scope_id, vehicle_profile_revision_id, capture_source_id, started_at DESC)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS discovery_marker_session_time " +
                "ON discovery_event_markers(capture_session_id, elapsed_realtime_nanos)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS android_capability_observation_captured " +
                "ON android_capability_observations(captured_at DESC)"
        )
    }

    /**
     * Schema-v3 drafts did not retain a vehicle/source scope, immutable template snapshot, boot
     * identity, or the authorizing gateway-health frame. Those rows are preserved verbatim but
     * quarantined instead of being upgraded with invented authority.
     */
    private fun quarantineUnscopedDiscoveryV3(database: SQLiteDatabase) {
        database.execSQL("DROP INDEX IF EXISTS one_active_discovery_capture")
        database.execSQL("DROP INDEX IF EXISTS discovery_capture_started")
        database.execSQL("DROP INDEX IF EXISTS discovery_marker_session_time")
        database.execSQL("DROP INDEX IF EXISTS android_capability_observation_captured")
        database.execSQL(
            "ALTER TABLE discovery_capture_sessions " +
                "RENAME TO discovery_capture_sessions_v3_unscoped"
        )
        database.execSQL(
            "ALTER TABLE discovery_event_markers " +
                "RENAME TO discovery_event_markers_v3_unscoped"
        )
        database.execSQL(
            "ALTER TABLE android_capability_observations " +
                "RENAME TO android_capability_observations_v3_unscoped"
        )
    }

    /**
     * Schema-v4 raw rows named a physical gateway but did not bind that observation to the vehicle
     * profile that was current when the bytes arrived. A gateway can be moved between vehicles, so
     * source_id alone is not a vehicle identity. Preserve every legacy row verbatim, but quarantine
     * it and every Discovery draft derived from it rather than inventing a vehicle lineage.
     */
    private fun quarantineUnscopedEvidenceV4(database: SQLiteDatabase) {
        listOf(
            "logical_frames_ingested",
            "can_observations_source_sequence",
            "logical_frames_scope_ingested",
            "can_observations_scope_sequence",
            "one_active_discovery_capture",
            "discovery_capture_started",
            "discovery_marker_session_time",
            "android_capability_observation_captured",
        ).forEach { database.execSQL("DROP INDEX IF EXISTS $it") }
        database.execSQL("ALTER TABLE logical_frames RENAME TO logical_frames_v4_unscoped")
        database.execSQL("ALTER TABLE can_observations RENAME TO can_observations_v4_unscoped")
        database.execSQL("ALTER TABLE import_receipts RENAME TO import_receipts_v4_unscoped")
        database.execSQL(
            "ALTER TABLE discovery_capture_sessions " +
                "RENAME TO discovery_capture_sessions_v4_unbound_evidence"
        )
        database.execSQL(
            "ALTER TABLE discovery_event_markers " +
                "RENAME TO discovery_event_markers_v4_unbound_evidence"
        )
        database.execSQL(
            "ALTER TABLE android_capability_observations " +
                "RENAME TO android_capability_observations_v4_unbound_evidence"
        )
    }

    @Synchronized
    fun upsertValidatedSource(source: PersistedSource) {
        val values = ContentValues().apply {
            put("source_id", source.sourceId)
            put("role", source.role.wireValue)
            put("bluetooth_address", source.bluetoothAddress)
            put("identity_json", source.identityJson)
            put("validated_at", source.validatedAt)
        }
        writableDatabase.insertWithOnConflict("sources", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun latestValidatedSources(): List<PersistedSource> {
        val latestByRole = linkedMapOf<DeviceRole, PersistedSource>()
        readableDatabase.query(
            "sources",
            arrayOf("source_id", "role", "bluetooth_address", "identity_json", "validated_at"),
            "bluetooth_address <> ?",
            arrayOf("IMPORTED"),
            null,
            null,
            "validated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val role = DeviceRole.entries.firstOrNull { it.wireValue == cursor.getString(1) }
                    ?: continue
                latestByRole.putIfAbsent(
                    role,
                    PersistedSource(
                        sourceId = cursor.getString(0),
                        role = role,
                        bluetoothAddress = cursor.getString(2),
                        identityJson = cursor.getString(3),
                        validatedAt = cursor.getString(4),
                    ),
                )
            }
        }
        return latestByRole.values.toList()
    }

    @Synchronized
    fun persistHeadUnitInventory(inventory: HeadUnitInventory): Boolean {
        inventory.validate()
        val inventoryJson = gson.toJson(inventory)
        val fingerprintJson = gson.toJson(
            inventory.copy(
                inventoryId = "00000000-0000-0000-0000-000000000000",
                capturedAt = "1970-01-01T00:00:00Z",
            )
        )
        return writableDatabase.insertWithOnConflict(
            "head_unit_inventory",
            null,
            ContentValues().apply {
                put("inventory_id", inventory.inventoryId)
                put("inventory_fingerprint", EvidenceBundles.sha256(fingerprintJson.toByteArray()))
                put("inventory_json", inventoryJson)
                put("captured_at", inventory.capturedAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    @Synchronized
    fun latestHeadUnitInventory(): HeadUnitInventory? = readableDatabase.query(
        "head_unit_inventory",
        arrayOf("inventory_json"),
        null, null, null, null, "id DESC", "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null
        else gson.fromJson(cursor.getString(0), HeadUnitInventory::class.java).validate()
    }

    @Synchronized
    fun appendVehicleProfile(profile: VehicleProfile) {
        profile.validate()
        val database = writableDatabase
        database.beginTransaction()
        try {
            val previous = latestVehicleProfile(database)
            require(profile.supersedesRevisionId == previous?.revisionId) {
                "Vehicle-profile revisions must append to the current revision."
            }
            database.insertOrThrow("vehicle_profiles", null, ContentValues().apply {
                put("revision_id", profile.revisionId)
                if (profile.supersedesRevisionId == null) putNull("supersedes_revision_id")
                else put("supersedes_revision_id", profile.supersedesRevisionId)
                put("profile_json", gson.toJson(profile))
                put("created_at", profile.createdAt)
            })
            appendUnknownHealthBaseline(database, profile.revisionId, profile.createdAt)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun latestVehicleProfile(): VehicleProfile? = latestVehicleProfile(readableDatabase)

    private fun latestVehicleProfile(database: SQLiteDatabase): VehicleProfile? = database.query(
        "vehicle_profiles",
        arrayOf("profile_json"),
        null, null, null, null, "id DESC", "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null
        else gson.fromJson(cursor.getString(0), VehicleProfile::class.java).validate()
    }

    @Synchronized
    fun ensureInitialUnknownHealthMap(recordedAt: Instant = Instant.now()) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val latest = latestHealthAssessments(database)
            VehicleSystem.entries.filterNot { it in latest }.forEach { system ->
                insertHealthAssessment(
                    database,
                    HealthAssessment.unknown(
                        system = system,
                        profileRevisionId = latestVehicleProfile(database)?.revisionId,
                        recordedAt = recordedAt.toString(),
                    ),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun appendHealthAssessment(assessment: HealthAssessment) {
        assessment.validate()
        val database = writableDatabase
        database.beginTransaction()
        try {
            val previous = latestHealthAssessments(database)[assessment.systemId]
            require(assessment.supersedesAssessmentId == previous?.assessmentId) {
                "Health assessments must append to the current system assessment."
            }
            require(assessment.profileRevisionId == latestVehicleProfile(database)?.revisionId) {
                "Health assessment must reference the current vehicle-profile revision."
            }
            insertHealthAssessment(database, assessment)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun latestHealthAssessments(): List<HealthAssessment> =
        latestHealthAssessments(readableDatabase).values.sortedBy { it.systemId.ordinal }

    private fun latestHealthAssessments(database: SQLiteDatabase): Map<VehicleSystem, HealthAssessment> {
        val latest = linkedMapOf<VehicleSystem, HealthAssessment>()
        database.query(
            "health_assessments",
            arrayOf("system_id", "assessment_json"),
            null, null, null, null, "id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val system = VehicleSystem.entries.firstOrNull { it.name == cursor.getString(0) }
                    ?: throw IllegalStateException("Stored health assessment has an unknown system ID.")
                if (system !in latest) {
                    latest[system] = gson.fromJson(
                        cursor.getString(1),
                        HealthAssessment::class.java,
                    ).validate()
                }
            }
        }
        return latest
    }

    private fun appendUnknownHealthBaseline(
        database: SQLiteDatabase,
        profileRevisionId: String,
        recordedAt: String,
    ) {
        val previous = latestHealthAssessments(database)
        VehicleSystem.entries.forEach { system ->
            insertHealthAssessment(
                database,
                HealthAssessment.unknown(
                    system = system,
                    profileRevisionId = profileRevisionId,
                    supersedesAssessmentId = previous[system]?.assessmentId,
                    recordedAt = recordedAt,
                ),
            )
        }
    }

    private fun insertHealthAssessment(database: SQLiteDatabase, assessment: HealthAssessment) {
        assessment.validate()
        database.insertOrThrow("health_assessments", null, ContentValues().apply {
            put("assessment_id", assessment.assessmentId)
            if (assessment.supersedesAssessmentId == null) putNull("supersedes_assessment_id")
            else put("supersedes_assessment_id", assessment.supersedesAssessmentId)
            if (assessment.profileRevisionId == null) putNull("profile_revision_id")
            else put("profile_revision_id", assessment.profileRevisionId)
            put("system_id", assessment.systemId.name)
            put("health_state", assessment.state.name)
            put("evidence_basis", assessment.basis.name)
            put("assessment_json", gson.toJson(assessment))
            put("recorded_at", assessment.recordedAt)
        })
    }

    @Synchronized
    fun digitalTwinSnapshot(exportedAt: Instant = Instant.now()): DigitalTwinSnapshot =
        DigitalTwinSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            exportedAt = exportedAt.toString(),
            databaseVersion = DATABASE_VERSION,
            headUnitInventory = latestHeadUnitInventory(),
            vehicleProfile = latestVehicleProfile(),
            healthAssessments = latestHealthAssessments(),
        ).validate()

    @Synchronized
    fun exportDigitalTwin(exportedAt: Instant = Instant.now()): ByteArray =
        gson.toJson(digitalTwinSnapshot(exportedAt)).toByteArray(Charsets.UTF_8)

    @Synchronized
    fun persistFrame(
        scope: DiscoveryEvidenceScope,
        sourceRole: DeviceRole,
        frame: GatewayFrame,
        envelope: ByteArray,
        ingestedAt: Instant = Instant.now(),
    ): Boolean {
        scope.validate()
        requireSourceRole(writableDatabase, scope.sourceId, sourceRole)
        if (resolveEvidenceScope(writableDatabase, scope.sourceId, sourceRole) != scope) return false
        val values = ContentValues().apply {
            put("vehicle_scope_id", scope.vehicleScopeId)
            put("vehicle_profile_revision_id", scope.vehicleProfileRevisionId)
            put("source_id", scope.sourceId)
            put("source_role", sourceRole.wireValue)
            put("source_sequence", frame.sequence.toString())
            put("source_monotonic_us", frame.monotonicMicroseconds.toString())
            put("protocol_major", frame.protocolMajor)
            put("protocol_minor", frame.protocolMinor)
            put("message_type", frame.messageType.code)
            put("flags", frame.flags)
            put("envelope", envelope)
            put("envelope_sha256", EvidenceBundles.sha256(envelope))
            put("ingested_at", ingestedAt.toString())
        }
        return writableDatabase.insertWithOnConflict(
            "logical_frames", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    @Synchronized
    fun persistCanObservation(
        scope: DiscoveryEvidenceScope,
        observation: CanObservation,
        ingestedAt: Instant = Instant.now(),
    ): Boolean {
        scope.validate()
        requireSourceRole(writableDatabase, scope.sourceId, DeviceRole.OBD_CAN)
        if (resolveEvidenceScope(writableDatabase, scope.sourceId, DeviceRole.OBD_CAN) != scope) return false
        return insertCanObservation(writableDatabase, scope, observation, ingestedAt)
    }

    private fun insertCanObservation(
        database: SQLiteDatabase,
        scope: DiscoveryEvidenceScope,
        observation: CanObservation,
        ingestedAt: Instant,
    ): Boolean {
        val values = ContentValues().apply {
            put("vehicle_scope_id", scope.vehicleScopeId)
            put("vehicle_profile_revision_id", scope.vehicleProfileRevisionId)
            put("source_id", scope.sourceId)
            put("session_id", observation.sessionId.toString())
            put("source_sequence", observation.sourceSequence.toString())
            put("source_monotonic_us", observation.monotonicMicroseconds.toString())
            put("bitrate_bps", observation.bitrateBps)
            put("identifier", observation.identifier.toLong())
            put("extended", if (observation.extended) 1 else 0)
            put("remote_request", if (observation.remoteRequest) 1 else 0)
            put("listen_only", if (observation.listenOnly) 1 else 0)
            put("data_length", observation.dataLength)
            put("data", observation.data)
            put("ingested_at", ingestedAt.toString())
        }
        return database.insertWithOnConflict(
            "can_observations", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    @Synchronized
    fun counts(): EvidenceCounts = EvidenceCounts(
        logicalFrames = scalarCount("logical_frames"),
        canObservations = scalarCount("can_observations"),
    )

    @Synchronized
    fun recentCanObservations(
        scope: DiscoveryEvidenceScope,
        limit: Int = 100_000,
    ): List<PersistedCanObservation> {
        scope.validate()
        require(limit in 1..100_000)
        val observations = mutableListOf<PersistedCanObservation>()
        readableDatabase.query(
            "can_observations",
            arrayOf(
                "vehicle_scope_id", "vehicle_profile_revision_id", "source_id", "session_id",
                "source_sequence", "source_monotonic_us",
                "bitrate_bps", "identifier", "extended", "remote_request", "listen_only",
                "data_length", "data",
            ),
            "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ?",
            scope.queryArguments(),
            null,
            null,
            "id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dataLength = cursor.getInt(11)
                val data = cursor.getBlob(12)
                require(dataLength in 0..8 && data.size == 8) {
                    "Persisted CAN observation payload shape is invalid."
                }
                observations += PersistedCanObservation(
                    vehicleScopeId = cursor.getString(0),
                    vehicleProfileRevisionId = cursor.getString(1),
                    sourceId = cursor.getString(2),
                    observation = CanObservation(
                        sessionId = cursor.getString(3).toUInt(),
                        sourceSequence = cursor.getString(4).toULong(),
                        monotonicMicroseconds = cursor.getString(5).toULong(),
                        bitrateBps = cursor.getInt(6),
                        identifier = cursor.getLong(7).toUInt(),
                        extended = cursor.getInt(8) == 1,
                        remoteRequest = cursor.getInt(9) == 1,
                        listenOnly = cursor.getInt(10) == 1,
                        dataLength = dataLength,
                        data = data,
                    ),
                )
            }
        }
        return observations.asReversed()
    }

    @Synchronized
    fun discoveryEvidenceSummary(scope: DiscoveryEvidenceScope): DiscoveryEvidenceSummary {
        scope.validate()
        return readableDatabase.rawQuery(
        """
        SELECT COUNT(*),
               COUNT(DISTINCT source_id || ':' || session_id),
               COUNT(DISTINCT extended || ':' || identifier),
               MIN(ingested_at),
               MAX(ingested_at)
        FROM can_observations
        WHERE vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ?
        """.trimIndent(),
        scope.queryArguments(),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "CAN evidence summary query returned no row." }
        DiscoveryEvidenceSummary(
            canObservations = cursor.getLong(0),
            canCaptureSessions = cursor.getLong(1),
            uniqueCanIdentifiers = cursor.getInt(2),
            firstIngestedAt = cursor.nullableString(3),
            lastIngestedAt = cursor.nullableString(4),
        )
    }
    }

    @Synchronized
    fun evidenceCounts(scope: DiscoveryEvidenceScope): EvidenceCounts {
        scope.validate()
        val logical = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM logical_frames WHERE " +
                "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ?",
            scope.queryArguments(),
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val can = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM can_observations WHERE " +
                "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ?",
            scope.queryArguments(),
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        return EvidenceCounts(logical, can)
    }

    @Synchronized
    fun resolveDiscoveryEvidenceScope(preferredSourceId: String? = null): DiscoveryEvidenceScope? {
        require(preferredSourceId == null || preferredSourceId.isNotBlank())
        val obdSources = latestValidatedSources().filter { it.role == DeviceRole.OBD_CAN }
        val source = preferredSourceId?.let { preferred ->
            obdSources.singleOrNull { it.sourceId == preferred }
        } ?: obdSources.singleOrNull() ?: return null
        return resolveEvidenceScope(readableDatabase, source.sourceId, DeviceRole.OBD_CAN)
    }

    /** Resolves the current immutable vehicle/profile binding for any validated VHOS source. */
    @Synchronized
    fun resolveEvidenceScope(sourceId: String): DiscoveryEvidenceScope? {
        require(sourceId.isNotBlank())
        return resolveEvidenceScope(readableDatabase, sourceId, requiredRole = null)
    }

    private fun resolveEvidenceScope(
        database: SQLiteDatabase,
        sourceId: String,
        requiredRole: DeviceRole?,
    ): DiscoveryEvidenceScope? {
        val role = database.query(
            "sources",
            arrayOf("role"),
            "source_id = ?",
            arrayOf(sourceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            DeviceRole.entries.firstOrNull { it.wireValue == cursor.getString(0) } ?: return null
        }
        if (requiredRole != null && role != requiredRole) return null
        val profile = latestVehicleProfile(database) ?: return null
        val stableVehicleIdentity = buildString {
            append(profile.vehiclePackId)
            append('|')
            append(profile.vehiclePackVersion)
            append('|')
            append(profile.vin ?: "profile-revision:${profile.revisionId}")
        }
        return DiscoveryEvidenceScope(
            vehicleScopeId = "vehicle-sha256:" +
                EvidenceBundles.sha256(stableVehicleIdentity.toByteArray()),
            vehicleProfileRevisionId = profile.revisionId,
            sourceId = sourceId,
        ).validate()
    }

    private fun requireSourceRole(
        database: SQLiteDatabase,
        sourceId: String,
        expectedRole: DeviceRole,
    ) {
        val role = database.query(
            "sources",
            arrayOf("role"),
            "source_id = ?",
            arrayOf(sourceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Evidence source is not validated." }
            cursor.getString(0)
        }
        require(role == expectedRole.wireValue) {
            "Evidence source role does not match the persisted frame role."
        }
    }

    private fun requireCurrentScope(
        database: SQLiteDatabase,
        expected: DiscoveryEvidenceScope,
        requiredRole: DeviceRole = DeviceRole.OBD_CAN,
    ) {
        val current = resolveEvidenceScope(database, expected.sourceId, requiredRole)
        require(current == expected) {
            "Discovery vehicle/profile/source scope changed; start a new capture for the current vehicle."
        }
    }

    @Synchronized
    fun latestDiscoveryEvidenceAnchor(scope: DiscoveryEvidenceScope): AndroidDiscoveryEvidenceAnchor? {
        scope.validate()
        return readableDatabase.query(
            "can_observations",
            arrayOf("source_id", "session_id", "source_sequence", "source_monotonic_us"),
            "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ?",
            scope.queryArguments(),
            null,
            null,
            "id DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else AndroidDiscoveryEvidenceAnchor(
                sourceId = cursor.getString(0),
                canSessionId = cursor.getString(1).toUInt(),
                sourceSequence = cursor.getString(2).toULong(),
                gatewayMonotonicMicroseconds = cursor.getString(3).toULong(),
            ).validate()
        }
    }

    @Synchronized
    fun containsDiscoveryEvidenceAnchor(
        scope: DiscoveryEvidenceScope,
        anchor: AndroidDiscoveryEvidenceAnchor,
    ): Boolean {
        scope.validate()
        anchor.validate()
        require(anchor.sourceId == scope.sourceId)
        return containsDiscoveryEvidenceAnchor(readableDatabase, scope, anchor)
    }

    private fun containsDiscoveryEvidenceAnchor(
        database: SQLiteDatabase,
        scope: DiscoveryEvidenceScope,
        anchor: AndroidDiscoveryEvidenceAnchor,
    ): Boolean = database.query(
            "can_observations",
            arrayOf("id"),
            "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ? " +
                "AND session_id = ? AND source_sequence = ? AND source_monotonic_us = ?",
            scope.queryArguments() + arrayOf(
                anchor.canSessionId.toString(),
                anchor.sourceSequence.toString(),
                anchor.gatewayMonotonicMicroseconds.toString(),
            ),
            null,
            null,
            null,
            "1",
        ).use(Cursor::moveToFirst)

    fun beginDiscoveryCapture(session: AndroidDiscoveryCaptureDraft) =
        beginDiscoveryCaptureAt(session, System.currentTimeMillis())

    /** Deterministic-clock entry point is internal to this module's instrumentation suite. */
    @Synchronized
    internal fun beginDiscoveryCaptureAt(
        session: AndroidDiscoveryCaptureDraft,
        nowEpochMillis: Long,
    ) {
        session.validate()
        require(session.state == AndroidCaptureDraftState.ACTIVE) {
            "A newly persisted AndroidDiscoveryCaptureDraft must be ACTIVE."
        }
        requireFreshDiscoveryAuthorization(session.safetyAuthorization, nowEpochMillis)
        val database = writableDatabase
        database.beginTransaction()
        try {
            val scope = session.evidenceScope()
            requireCurrentScope(database, scope)
            if (session.safetyAuthorization.mutationAuthority ==
                AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP
            ) {
                require(containsDiscoveryEvidenceAnchor(
                    database,
                    scope,
                    requireNotNull(session.startAnchor),
                )) { "Selector bootstrap start RAW_CAN lineage is not retained." }
            }
            val activeExists = database.query(
                "discovery_capture_sessions",
                arrayOf("session_id"),
                "state = ?",
                arrayOf(AndroidCaptureDraftState.ACTIVE.name),
                null,
                null,
                null,
                "1",
            ).use { it.moveToFirst() }
            require(!activeExists) { "A Discovery AndroidDiscoveryCaptureDraft is already active." }
            database.insertOrThrow(
                "discovery_capture_sessions",
                null,
                captureSessionStartValues(session),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun finalizeDiscoveryCapture(session: AndroidDiscoveryCaptureDraft) =
        finalizeDiscoveryCaptureAt(session, System.currentTimeMillis())

    /** Deterministic-clock entry point is internal to this module's instrumentation suite. */
    @Synchronized
    internal fun finalizeDiscoveryCaptureAt(
        session: AndroidDiscoveryCaptureDraft,
        nowEpochMillis: Long,
    ) {
        session.validate()
        require(session.state != AndroidCaptureDraftState.ACTIVE) {
            "Final AndroidDiscoveryCaptureDraft state must be COMPLETED or ABORTED."
        }
        if (session.state == AndroidCaptureDraftState.COMPLETED) {
            requireFreshDiscoveryAuthorization(
                requireNotNull(session.finalizationSafetyAuthorization),
                nowEpochMillis,
            )
        }
        val database = writableDatabase
        database.beginTransaction()
        try {
            val stored = requireNotNull(captureSessionById(database, session.sessionId)) {
                "AndroidDiscoveryCaptureDraft ${session.sessionId} does not exist."
            }
            require(stored.state == AndroidCaptureDraftState.ACTIVE) {
                "AndroidDiscoveryCaptureDraft ${session.sessionId} was already finalized."
            }
            if (session.state == AndroidCaptureDraftState.COMPLETED) {
                requireCurrentScope(database, stored.evidenceScope())
                if (session.safetyAuthorization.mutationAuthority ==
                    AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP
                ) {
                    require(hasExactSelectorBootstrapMarkers(database, stored)) {
                        "Selector bootstrap cannot complete until its installed marker sequence is exact."
                    }
                    require(containsDiscoveryEvidenceAnchor(
                        database,
                        stored.evidenceScope(),
                        requireNotNull(session.endAnchor),
                    )) { "Selector bootstrap final RAW_CAN lineage is not retained." }
                }
            }
            require(
                stored.vehicleScopeId == session.vehicleScopeId &&
                    stored.vehicleProfileRevisionId == session.vehicleProfileRevisionId &&
                    stored.sourceId == session.sourceId &&
                    stored.testTemplateId == session.testTemplateId &&
                    stored.testTemplateVersion == session.testTemplateVersion &&
                    stored.testTemplateSnapshot == session.testTemplateSnapshot &&
                    stored.startedAt == session.startedAt &&
                    stored.startedElapsedRealtimeNanos == session.startedElapsedRealtimeNanos &&
                    stored.startedBootId == session.startedBootId &&
                    stored.startAnchor == session.startAnchor &&
                    stored.startLogicalFrameCount == session.startLogicalFrameCount &&
                    stored.startCanObservationCount == session.startCanObservationCount &&
                    stored.safetyEvidence == session.safetyEvidence &&
                    stored.safetyAuthorization == session.safetyAuthorization
            ) { "AndroidDiscoveryCaptureDraft immutable start metadata changed during finalization." }
            val updated = database.update(
                "discovery_capture_sessions",
                ContentValues().apply {
                    put("state", session.state.name)
                    put("ended_at", session.endedAt)
                    put(
                        "ended_elapsed_realtime_nanos",
                        requireNotNull(session.endedElapsedRealtimeNanos).toString(),
                    )
                    put("ended_boot_id", requireNotNull(session.endedBootId))
                    putAnchor("end", session.endAnchor)
                    put("end_logical_frame_count", requireNotNull(session.endLogicalFrameCount))
                    put("end_can_observation_count", requireNotNull(session.endCanObservationCount))
                    put("finalization_authority", requireNotNull(session.finalizationAuthority).name)
                    putSafetyAuthorization("finalization", session.finalizationSafetyAuthorization)
                },
                "session_id = ? AND state = ?",
                arrayOf(session.sessionId, AndroidCaptureDraftState.ACTIVE.name),
            )
            check(updated == 1) { "AndroidDiscoveryCaptureDraft finalization did not update exactly one active row." }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun appendDiscoveryMarker(marker: AndroidDiscoveryMarkerRecord) =
        appendDiscoveryMarkerAt(marker, System.currentTimeMillis())

    /** Deterministic-clock entry point is internal to this module's instrumentation suite. */
    @Synchronized
    internal fun appendDiscoveryMarkerAt(
        marker: AndroidDiscoveryMarkerRecord,
        nowEpochMillis: Long,
    ) {
        marker.validate()
        requireFreshDiscoveryAuthorization(marker.safetyAuthorization, nowEpochMillis)
        val database = writableDatabase
        database.beginTransaction()
        try {
            val capture = requireNotNull(captureSessionById(database, marker.captureSessionId)) {
                "AndroidDiscoveryMarkerRecord AndroidDiscoveryCaptureDraft does not exist."
            }
            require(capture.state == AndroidCaptureDraftState.ACTIVE) {
                "AndroidDiscoveryMarkerRecord can only be appended to an active AndroidDiscoveryCaptureDraft."
            }
            val captureScope = capture.evidenceScope()
            require(marker.safetyAuthorization.sourceId == captureScope.sourceId &&
                marker.safetyAuthorization.mutationAuthority ==
                capture.safetyAuthorization.mutationAuthority
            ) { "Marker mutation authority does not match the active capture." }
            if (capture.safetyAuthorization.mutationAuthority ==
                AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP
            ) {
                require(marker.safetyAuthorization.captureSessionId ==
                    capture.safetyAuthorization.captureSessionId
                ) { "Selector marker crossed gateway capture sessions." }
                require(containsDiscoveryEvidenceAnchor(
                    database,
                    captureScope,
                    requireNotNull(marker.evidenceAnchor),
                )) { "Selector marker RAW_CAN lineage is not retained." }
                val markerCount = database.rawQuery(
                    "SELECT COUNT(*) FROM discovery_event_markers WHERE capture_session_id = ?",
                    arrayOf(capture.sessionId),
                ).use { count -> check(count.moveToFirst()); count.getInt(0) }
                val expected = capture.testTemplateSnapshot.markers.getOrNull(markerCount)
                require(expected != null && marker.matches(expected)) {
                    "Selector bootstrap markers must follow the exact installed sequence."
                }
            }
            requireCurrentScope(database, captureScope)
            database.insertOrThrow("discovery_event_markers", null, ContentValues().apply {
                put("marker_id", marker.markerId)
                put("capture_session_id", marker.captureSessionId)
                put("event_type", marker.eventType)
                put("label", marker.label)
                put("marker_kind", marker.kind.name)
                putNullable("value_text", marker.value)
                putNullable("unit", marker.unit)
                put("observed_at", marker.observedAt)
                put("elapsed_realtime_nanos", marker.elapsedRealtimeNanos.toString())
                putAnchor(null, marker.evidenceAnchor)
                put("observer", marker.observer)
                putNullable("note", marker.note)
                putSafetyAuthorization(null, marker.safetyAuthorization)
            })
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun activeDiscoveryCapture(): PersistedAndroidDiscoveryCapture? = readableDatabase.query(
        "discovery_capture_sessions",
        CAPTURE_SESSION_COLUMNS,
        "state = ?",
        arrayOf(AndroidCaptureDraftState.ACTIVE.name),
        null,
        null,
        "started_at DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else persistedDiscoveryCapture(readableDatabase, cursor)
    }

    @Synchronized
    fun recentDiscoveryCaptures(
        limit: Int = 100,
        scope: DiscoveryEvidenceScope? = null,
    ): List<PersistedAndroidDiscoveryCapture> {
        require(limit in 1..1_000)
        val result = mutableListOf<PersistedAndroidDiscoveryCapture>()
        readableDatabase.query(
            "discovery_capture_sessions",
            CAPTURE_SESSION_COLUMNS,
            scope?.let {
                "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND capture_source_id = ?"
            },
            scope?.queryArguments(),
            null,
            null,
            "started_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += persistedDiscoveryCapture(readableDatabase, cursor)
        }
        return result
    }

    @Synchronized
    fun eventMarkers(captureSessionId: String): List<AndroidDiscoveryMarkerRecord> {
        require(captureSessionId.isNotBlank())
        val result = mutableListOf<AndroidDiscoveryMarkerRecord>()
        readableDatabase.query(
            "discovery_event_markers",
            arrayOf(
                "marker_id", "capture_session_id", "event_type", "label", "marker_kind",
                "value_text", "unit", "observed_at", "elapsed_realtime_nanos", "source_id",
                "can_session_id", "nearest_source_sequence", "nearest_gateway_monotonic_us",
                "observer", "note", "safety_authorization_source_id",
                "safety_authorization_frame_sequence",
                "safety_authorization_gateway_monotonic_us",
                "safety_authorization_received_at_ms", "safety_authorization_extension_json",
            ),
            "capture_session_id = ?",
            arrayOf(captureSessionId),
            null,
            null,
            "CAST(elapsed_realtime_nanos AS INTEGER), marker_id",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AndroidDiscoveryMarkerRecord(
                    markerId = cursor.getString(0),
                    captureSessionId = cursor.getString(1),
                    eventType = cursor.getString(2),
                    label = cursor.getString(3),
                    kind = AndroidDiscoveryMarkerKind.valueOf(cursor.getString(4)),
                    value = cursor.nullableString(5),
                    unit = cursor.nullableString(6),
                    observedAt = cursor.getString(7),
                    elapsedRealtimeNanos = cursor.getString(8).toLong(),
                    evidenceAnchor = cursor.anchor(9, 10, 11, 12),
                    observer = cursor.getString(13),
                    note = cursor.nullableString(14),
                    safetyAuthorization = cursor.safetyAuthorization(15, 16, 17, 18, 19),
                ).validate()
            }
        }
        return result
    }

    fun persistAndroidVehicleCapabilityObservation(snapshot: AndroidVehicleCapabilityObservation): Boolean =
        persistAndroidVehicleCapabilityObservationAt(snapshot, System.currentTimeMillis())

    /** Deterministic-clock entry point is internal to this module's instrumentation suite. */
    @Synchronized
    internal fun persistAndroidVehicleCapabilityObservationAt(
        snapshot: AndroidVehicleCapabilityObservation,
        nowEpochMillis: Long,
    ): Boolean {
        snapshot.validate()
        requireFreshDiscoveryAuthorization(snapshot.safetyAuthorization, nowEpochMillis)
        val scope = DiscoveryEvidenceScope(
            vehicleScopeId = snapshot.vehicleScopeId,
            vehicleProfileRevisionId = snapshot.vehicleProfileRevisionId,
            sourceId = snapshot.sourceId,
        ).validate()
        val snapshotJson = gson.toJson(snapshot)
        val fingerprintJson = gson.toJson(
            snapshot.copy(
                snapshotId = "00000000-0000-0000-0000-000000000000",
                capturedAt = "1970-01-01T00:00:00Z",
                safetyAuthorization = snapshot.safetyAuthorization.copy(
                    healthFrameSequence = 0UL,
                    healthGatewayMonotonicMicroseconds = 0UL,
                    receivedAtEpochMillis = 0L,
                ),
            )
        )
        val database = writableDatabase
        database.beginTransaction()
        try {
            // This is the final authority check inside the same transaction as the append. An
            // Activity-level check cannot prevent a profile revision changing in between calls.
            requireCurrentScope(database, scope)
            val inserted = database.insertWithOnConflict(
                "android_capability_observations",
                null,
                ContentValues().apply {
                    put("snapshot_id", snapshot.snapshotId)
                    put("snapshot_fingerprint", EvidenceBundles.sha256(fingerprintJson.toByteArray()))
                    put("snapshot_json", snapshotJson)
                    put("captured_at", snapshot.capturedAt)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
            database.setTransactionSuccessful()
            return inserted
        } finally {
            database.endTransaction()
        }
    }

    /**
     * The encrypted append boundary independently rechecks receipt freshness. UI checks improve
     * responsiveness, but they are not persistence authority and cannot make stale lineage valid.
     */
    private fun requireFreshDiscoveryAuthorization(
        authorization: AndroidDiscoverySafetyAuthorization,
        nowEpochMillis: Long,
    ) {
        authorization.validate()
        val freshnessMillis = when (authorization.mutationAuthority) {
            AndroidDiscoveryMutationAuthority.PARKED ->
                AndroidDiscoveryEngineeringSafetyGate.HEALTH_FRESHNESS_MILLIS
            AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP ->
                AndroidDiscoveryPassiveBootstrapPolicy.FRESHNESS_MILLIS
        }
        require(nowEpochMillis >= 0) { "Discovery mutation clock is invalid." }
        require(nowEpochMillis - authorization.receivedAtEpochMillis in 0..freshnessMillis) {
            "Discovery mutation health authorization is stale or future-dated."
        }
        if (authorization.mutationAuthority ==
            AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP
        ) {
            val rawReceipt = requireNotNull(authorization.rawCanReceivedAtEpochMillis)
            require(nowEpochMillis - rawReceipt in 0..freshnessMillis) {
                "Selector bootstrap RAW_CAN authorization is stale or future-dated."
            }
        }
    }

    @Synchronized
    fun recentAndroidVehicleCapabilityObservations(
        limit: Int = 25,
        scope: DiscoveryEvidenceScope? = null,
    ): List<AndroidVehicleCapabilityObservation> {
        require(limit in 1..500)
        val result = mutableListOf<AndroidVehicleCapabilityObservation>()
        readableDatabase.query(
            "android_capability_observations",
            arrayOf("snapshot_json"),
            null,
            null,
            null,
            null,
            "captured_at DESC",
            if (scope == null) limit.toString() else "500",
        ).use { cursor ->
            while (cursor.moveToNext() && result.size < limit) {
                val observation = gson.fromJson(
                    cursor.getString(0),
                    AndroidVehicleCapabilityObservation::class.java,
                ).validate()
                if (scope == null || (
                        observation.vehicleScopeId == scope.vehicleScopeId &&
                            observation.vehicleProfileRevisionId == scope.vehicleProfileRevisionId &&
                            observation.sourceId == scope.sourceId
                        )
                ) result += observation
            }
        }
        return result
    }

    private fun captureSessionStartValues(session: AndroidDiscoveryCaptureDraft): ContentValues = ContentValues().apply {
        put("session_id", session.sessionId)
        put("vehicle_scope_id", session.vehicleScopeId)
        put("vehicle_profile_revision_id", session.vehicleProfileRevisionId)
        put("capture_source_id", session.sourceId)
        put("test_template_id", session.testTemplateId)
        put("test_template_version", session.testTemplateVersion)
        val templateJson = gson.toJson(session.testTemplateSnapshot)
        put("test_template_snapshot_json", templateJson)
        put("test_template_snapshot_sha256", EvidenceBundles.sha256(templateJson.toByteArray()))
        put("state", session.state.name)
        put("started_at", session.startedAt)
        put("started_elapsed_realtime_nanos", session.startedElapsedRealtimeNanos.toString())
        put("started_boot_id", session.startedBootId)
        putAnchor("start", session.startAnchor)
        put("start_logical_frame_count", session.startLogicalFrameCount)
        put("start_can_observation_count", session.startCanObservationCount)
        put("safety_evidence", session.safetyEvidence.name)
        putSafetyAuthorization(null, session.safetyAuthorization)
    }

    private fun AndroidDiscoveryCaptureDraft.evidenceScope(): DiscoveryEvidenceScope =
        DiscoveryEvidenceScope(
            vehicleScopeId = vehicleScopeId,
            vehicleProfileRevisionId = vehicleProfileRevisionId,
            sourceId = sourceId,
        ).validate()

    private fun captureSessionById(database: SQLiteDatabase, sessionId: String): AndroidDiscoveryCaptureDraft? =
        database.query(
            "discovery_capture_sessions",
            CAPTURE_SESSION_COLUMNS,
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (!cursor.moveToFirst()) null else captureSession(cursor) }

    private fun persistedDiscoveryCapture(
        database: SQLiteDatabase,
        cursor: Cursor,
    ): PersistedAndroidDiscoveryCapture {
        val session = captureSession(cursor)
        val eventCount = database.rawQuery(
            "SELECT COUNT(*) FROM discovery_event_markers WHERE capture_session_id = ?",
            arrayOf(session.sessionId),
        ).use { count -> check(count.moveToFirst()); count.getInt(0) }
        return PersistedAndroidDiscoveryCapture(session, eventCount)
    }

    private fun hasExactSelectorBootstrapMarkers(
        database: SQLiteDatabase,
        capture: AndroidDiscoveryCaptureDraft,
    ): Boolean {
        val observed = mutableListOf<AndroidDiscoveryMarkerDefinition>()
        database.query(
            "discovery_event_markers",
            arrayOf("event_type", "label", "marker_kind", "unit"),
            "capture_session_id = ?",
            arrayOf(capture.sessionId),
            null,
            null,
            "rowid ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                observed += AndroidDiscoveryMarkerDefinition(
                    eventType = cursor.getString(0),
                    label = cursor.getString(1),
                    kind = AndroidDiscoveryMarkerKind.valueOf(cursor.getString(2)),
                    suggestedUnit = cursor.nullableString(3),
                ).validate()
            }
        }
        return observed == capture.testTemplateSnapshot.markers
    }

    private fun AndroidDiscoveryMarkerRecord.matches(
        definition: AndroidDiscoveryMarkerDefinition,
    ): Boolean = eventType == definition.eventType && label == definition.label &&
        kind == definition.kind && unit == definition.suggestedUnit

    private fun captureSession(cursor: Cursor): AndroidDiscoveryCaptureDraft =
        gson.fromJson(cursor.getString(6), AndroidDiscoveryTestTemplate::class.java).let { template ->
            val templateJson = gson.toJson(template.validate())
            require(EvidenceBundles.sha256(templateJson.toByteArray()) == cursor.getString(7)) {
                "Persisted Discovery test-template snapshot hash does not match."
            }
            AndroidDiscoveryCaptureDraft(
                sessionId = cursor.getString(0),
                vehicleScopeId = cursor.getString(1),
                vehicleProfileRevisionId = cursor.getString(2),
                sourceId = cursor.getString(3),
                testTemplateId = cursor.getString(4),
                testTemplateVersion = cursor.getString(5),
                testTemplateSnapshot = template,
                state = AndroidCaptureDraftState.valueOf(cursor.getString(8)),
                startedAt = cursor.getString(9),
                startedElapsedRealtimeNanos = cursor.getString(10).toLong(),
                startedBootId = cursor.getString(11),
                endedAt = cursor.nullableString(12),
                endedElapsedRealtimeNanos = cursor.nullableString(13)?.toLong(),
                endedBootId = cursor.nullableString(14),
                startAnchor = cursor.anchor(15, 16, 17, 18),
                endAnchor = cursor.anchor(19, 20, 21, 22),
                startLogicalFrameCount = cursor.getLong(23),
                startCanObservationCount = cursor.getLong(24),
                endLogicalFrameCount = cursor.nullableLong(25),
                endCanObservationCount = cursor.nullableLong(26),
                safetyEvidence = AndroidDiscoverySafetyEvidence.valueOf(cursor.getString(27)),
                safetyAuthorization = cursor.safetyAuthorization(28, 29, 30, 31, 37),
                finalizationAuthority = cursor.nullableString(32)?.let(
                    AndroidCaptureFinalizationAuthority::valueOf
                ),
                finalizationSafetyAuthorization = cursor.nullableSafetyAuthorization(33, 34, 35, 36, 38),
            ).validate()
        }

    private fun ContentValues.putSafetyAuthorization(
        prefix: String?,
        authorization: AndroidDiscoverySafetyAuthorization?,
    ) {
        val base = prefix?.let { "${it}_authorization" } ?: "safety_authorization"
        val extensionColumn = "${base}_extension_json"
        if (authorization == null) {
            putNull("${base}_source_id")
            putNull("${base}_frame_sequence")
            putNull("${base}_gateway_monotonic_us")
            putNull("${base}_received_at_ms")
            putNull(extensionColumn)
        } else {
            authorization.validate()
            put("${base}_source_id", authorization.sourceId)
            put("${base}_frame_sequence", authorization.healthFrameSequence.toString())
            put("${base}_gateway_monotonic_us", authorization.healthGatewayMonotonicMicroseconds.toString())
            put("${base}_received_at_ms", authorization.receivedAtEpochMillis)
            put(extensionColumn, gson.toJson(StoredDiscoveryAuthorizationV1.from(authorization)))
        }
    }

    private fun Cursor.safetyAuthorization(
        sourceIndex: Int,
        sequenceIndex: Int,
        monotonicIndex: Int,
        receivedAtIndex: Int,
        extensionIndex: Int,
    ): AndroidDiscoverySafetyAuthorization {
        val legacy = AndroidDiscoverySafetyAuthorization(
            sourceId = getString(sourceIndex),
            healthFrameSequence = getString(sequenceIndex).toULong(),
            healthGatewayMonotonicMicroseconds = getString(monotonicIndex).toULong(),
            receivedAtEpochMillis = getLong(receivedAtIndex),
            // Pre-v6 rows were written only after a handshake that already rejected non-listen-only
            // gateways. Preserve that proven invariant without inventing bootstrap lineage.
            listenOnlyProven = true,
        )
        return nullableString(extensionIndex)?.let {
            gson.fromJson(it, StoredDiscoveryAuthorizationV1::class.java).toAuthorization(legacy)
        } ?: legacy.validate()
    }

    private fun Cursor.nullableSafetyAuthorization(
        sourceIndex: Int,
        sequenceIndex: Int,
        monotonicIndex: Int,
        receivedAtIndex: Int,
        extensionIndex: Int,
    ): AndroidDiscoverySafetyAuthorization? = if (isNull(sourceIndex)) {
        require(isNull(sequenceIndex) && isNull(monotonicIndex) && isNull(receivedAtIndex) &&
            isNull(extensionIndex)
        ) {
            "Partial Discovery PARKED authorization lineage is invalid."
        }
        null
    } else {
        safetyAuthorization(sourceIndex, sequenceIndex, monotonicIndex, receivedAtIndex, extensionIndex)
    }

    private fun ContentValues.putAnchor(prefix: String?, anchor: AndroidDiscoveryEvidenceAnchor?) {
        val sourceColumn = prefix?.let { "${it}_source_id" } ?: "source_id"
        val sessionColumn = prefix?.let { "${it}_can_session_id" } ?: "can_session_id"
        val sequenceColumn = prefix?.let { "${it}_source_sequence" } ?: "nearest_source_sequence"
        val monotonicColumn = prefix?.let { "${it}_gateway_monotonic_us" }
            ?: "nearest_gateway_monotonic_us"
        if (anchor == null) {
            putNull(sourceColumn)
            putNull(sessionColumn)
            putNull(sequenceColumn)
            putNull(monotonicColumn)
        } else {
            anchor.validate()
            put(sourceColumn, anchor.sourceId)
            put(sessionColumn, anchor.canSessionId.toString())
            put(sequenceColumn, anchor.sourceSequence.toString())
            put(monotonicColumn, anchor.gatewayMonotonicMicroseconds.toString())
        }
    }

    private fun ContentValues.putNullable(column: String, value: String?) {
        if (value == null) putNull(column) else put(column, value)
    }

    private fun Cursor.anchor(
        sourceIndex: Int,
        sessionIndex: Int,
        sequenceIndex: Int,
        monotonicIndex: Int,
    ): AndroidDiscoveryEvidenceAnchor? {
        val source = nullableString(sourceIndex) ?: return null
        return AndroidDiscoveryEvidenceAnchor(
            sourceId = source,
            canSessionId = getString(sessionIndex).toUInt(),
            sourceSequence = getString(sequenceIndex).toULong(),
            gatewayMonotonicMicroseconds = getString(monotonicIndex).toULong(),
        ).validate()
    }

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    @Synchronized
    fun recentPortableFrames(
        scope: DiscoveryEvidenceScope,
        limit: Int = 20_000,
    ): List<PortableEvidenceRecord> {
        scope.validate()
        require(limit in 1..100_000)
        val records = mutableListOf<PortableEvidenceRecord>()
        readableDatabase.query(
            "logical_frames",
            arrayOf(
                "source_role", "source_id", "source_sequence", "source_monotonic_us",
                "protocol_major", "protocol_minor", "message_type", "flags", "ingested_at",
                "envelope_sha256", "envelope",
            ),
            "vehicle_scope_id = ? AND vehicle_profile_revision_id = ? AND source_id = ?",
            scope.queryArguments(),
            null,
            null,
            "id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val envelope = cursor.getBlob(10)
                records += PortableEvidenceRecord(
                    sourceRole = cursor.getString(0),
                    sourceId = cursor.getString(1),
                    sourceSequence = cursor.getString(2),
                    sourceMonotonicMicroseconds = cursor.getString(3),
                    protocolMajor = cursor.getInt(4),
                    protocolMinor = cursor.getInt(5),
                    messageType = cursor.getInt(6),
                    flags = cursor.getInt(7),
                    ingestedAt = cursor.getString(8),
                    envelopeSha256 = cursor.getString(9),
                    envelopeBase64 = Base64.getEncoder().encodeToString(envelope),
                )
            }
        }
        return records.asReversed()
    }

    @Synchronized
    fun importBundle(
        bundle: ImportedEvidenceBundle,
        scope: DiscoveryEvidenceScope,
        importedAt: Instant = Instant.now(),
    ): Int {
        scope.validate()
        val database = writableDatabase
        database.beginTransaction()
        try {
            requireCurrentScope(database, scope)
            val receiptExists = database.query(
                "import_receipts", arrayOf("bundle_id"),
                "bundle_id = ? AND manifest_sha256 = ? AND vehicle_scope_id = ? " +
                    "AND vehicle_profile_revision_id = ?",
                arrayOf(
                    bundle.manifest.bundleId,
                    bundle.manifestSha256,
                    scope.vehicleScopeId,
                    scope.vehicleProfileRevisionId,
                ),
                null, null, null, "1",
            ).use { it.moveToFirst() }
            if (receiptExists) {
                database.setTransactionSuccessful()
                return 0
            }
            var inserted = 0
            bundle.records.forEach { record ->
                val role = DeviceRole.entries.firstOrNull { it.wireValue == record.sourceRole }
                    ?: throw IllegalArgumentException("Unknown evidence source role ${record.sourceRole}.")
                val envelope = record.verifyEnvelope()
                val frame = GatewayFrame.decode(envelope)
                if (frame.sequence.toString() != record.sourceSequence ||
                    frame.monotonicMicroseconds.toString() != record.sourceMonotonicMicroseconds ||
                    frame.protocolMajor != record.protocolMajor || frame.protocolMinor != record.protocolMinor ||
                    frame.messageType.code != record.messageType || frame.flags != record.flags
                ) {
                    throw IllegalArgumentException("Portable record metadata does not match its VHOS envelope.")
                }
                ensureImportedSource(database, record.sourceId, role, importedAt)
                if (role == DeviceRole.OBD_CAN) {
                    require(record.sourceId == scope.sourceId) {
                        "Imported OBD/CAN evidence source does not match the selected vehicle gateway."
                    }
                }
                val recordScope = scope.copy(sourceId = record.sourceId).validate()
                val values = ContentValues().apply {
                    put("vehicle_scope_id", recordScope.vehicleScopeId)
                    put("vehicle_profile_revision_id", recordScope.vehicleProfileRevisionId)
                    put("source_id", record.sourceId)
                    put("source_role", role.wireValue)
                    put("source_sequence", record.sourceSequence)
                    put("source_monotonic_us", record.sourceMonotonicMicroseconds)
                    put("protocol_major", record.protocolMajor)
                    put("protocol_minor", record.protocolMinor)
                    put("message_type", record.messageType)
                    put("flags", record.flags)
                    put("envelope", envelope)
                    put("envelope_sha256", record.envelopeSha256.lowercase())
                    put("ingested_at", record.ingestedAt)
                }
                if (database.insertWithOnConflict(
                        "logical_frames", null, values, SQLiteDatabase.CONFLICT_IGNORE
                    ) != -1L
                ) inserted++
                if (role == DeviceRole.OBD_CAN) {
                    val evidenceIngestedAt = try {
                        Instant.parse(record.ingestedAt)
                    } catch (error: RuntimeException) {
                        throw IllegalArgumentException(
                            "Portable evidence record has an invalid ingestion timestamp.",
                            error,
                        )
                    }
                    frame.decodeCanObservations().forEach { observation ->
                        if (!observation.listenOnly) {
                            throw IllegalArgumentException(
                                "Imported CAN evidence does not retain listen-only proof."
                            )
                        }
                        insertCanObservation(
                            database,
                            recordScope,
                            observation,
                            evidenceIngestedAt,
                        )
                    }
                }
            }
            database.insertOrThrow("import_receipts", null, ContentValues().apply {
                put("bundle_id", bundle.manifest.bundleId)
                put("manifest_sha256", bundle.manifestSha256)
                put("vehicle_scope_id", scope.vehicleScopeId)
                put("vehicle_profile_revision_id", scope.vehicleProfileRevisionId)
                put("imported_at", importedAt.toString())
                put("record_count", bundle.records.size)
            })
            database.setTransactionSuccessful()
            return inserted
        } finally {
            database.endTransaction()
        }
    }

    private fun ensureImportedSource(
        database: SQLiteDatabase,
        sourceId: String,
        role: DeviceRole,
        importedAt: Instant,
    ) {
        database.insertWithOnConflict("sources", null, ContentValues().apply {
            put("source_id", sourceId)
            put("role", role.wireValue)
            put("bluetooth_address", "IMPORTED")
            put("identity_json", "{\"evidence_source\":\"cross-platform-import\"}")
            put("validated_at", importedAt.toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun scalarCount(table: String): Long = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM $table", null)
        .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    @Synchronized
    override fun close() {
        super.close()
        databasePassphrase.fill(0)
        synchronized(INSTANCE_LOCK) {
            if (instance === this) instance = null
        }
    }

    companion object {
        internal const val DATABASE_NAME = "vhos-evidence.db"
        private const val DATABASE_VERSION = 6
        private val CAPTURE_SESSION_COLUMNS = arrayOf(
            "session_id", "vehicle_scope_id", "vehicle_profile_revision_id", "capture_source_id",
            "test_template_id", "test_template_version", "test_template_snapshot_json",
            "test_template_snapshot_sha256", "state", "started_at",
            "started_elapsed_realtime_nanos", "started_boot_id", "ended_at",
            "ended_elapsed_realtime_nanos", "ended_boot_id", "start_source_id",
            "start_can_session_id", "start_source_sequence", "start_gateway_monotonic_us",
            "end_source_id", "end_can_session_id", "end_source_sequence",
            "end_gateway_monotonic_us", "start_logical_frame_count",
            "start_can_observation_count", "end_logical_frame_count",
            "end_can_observation_count", "safety_evidence", "safety_authorization_source_id",
            "safety_authorization_frame_sequence", "safety_authorization_gateway_monotonic_us",
            "safety_authorization_received_at_ms", "finalization_authority",
            "finalization_authorization_source_id", "finalization_authorization_frame_sequence",
            "finalization_authorization_gateway_monotonic_us",
            "finalization_authorization_received_at_ms",
            "safety_authorization_extension_json",
            "finalization_authorization_extension_json",
        )
        private val INSTANCE_LOCK = Any()
        // SQLiteOpenHelper retains only the application context supplied at construction.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: EvidenceDatabase? = null
        private val gson: Gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .disableHtmlEscaping()
            .create()

        /**
         * Opens the single process-wide truth store. Call from a worker thread because first use may
         * perform a verified plaintext-to-SQLCipher migration.
         */
        fun open(context: Context): EvidenceDatabase {
            instance?.let { return it }
            return synchronized(INSTANCE_LOCK) {
                instance?.let { return@synchronized it }
                EvidenceStoreNativeLibrary.load()
                val passphrase = EvidenceStoreKeyManager(context).getOrCreatePassphrase(
                    allowEnvelopeCreation = EncryptedEvidenceStoreMigrator.mayCreateKeyEnvelope(
                        context,
                        DATABASE_NAME,
                    ),
                )
                var database: EvidenceDatabase? = null
                try {
                    val migrationState = EncryptedEvidenceStoreMigrator.prepare(
                        context = context,
                        databaseName = DATABASE_NAME,
                        passphrase = passphrase,
                    )
                    database = EvidenceDatabase(context, passphrase, migrationState)
                    val connection = database.writableDatabase
                    val cipherVersion = connection.rawQuery(
                        "PRAGMA cipher_version",
                        emptyArray<String>(),
                    ).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0).isNotBlank()) {
                            "SQLCipher did not report an active cipher version."
                        }
                        cursor.getString(0)
                    }
                    check(!EncryptedEvidenceStoreMigrator.hasPlaintextHeader(
                        context.applicationContext.getDatabasePath(DATABASE_NAME)
                    )) {
                        "The live evidence database still exposes a plaintext SQLite header."
                    }
                    database.securityStatus = EvidenceStoreSecurity(
                        encryptedAtRest = true,
                        cipherVersion = cipherVersion,
                        keyProtection = "Android Keystore AES-256-GCM envelope",
                        keyEnvelopeVersion = EvidenceStoreKeyManager.KEY_ENVELOPE_VERSION,
                        migrationState = database.initialMigrationState,
                    )
                    instance = database
                    database
                } catch (error: Exception) {
                    database?.close() ?: passphrase.fill(0)
                    throw EvidenceStoreSecurityException(
                        "The encrypted evidence store could not be opened safely: " +
                            (error.message ?: error.javaClass.simpleName),
                        error,
                    )
                }
            }
        }

        internal fun closeForInstrumentationTests() {
            synchronized(INSTANCE_LOCK) {
                instance?.close()
                instance = null
            }
        }
    }
}
