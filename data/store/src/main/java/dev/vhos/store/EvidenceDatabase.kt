package dev.vhos.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
    val sourceId: String,
    val observation: CanObservation,
)

class EvidenceDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        database.setForeignKeyConstraintsEnabled(true)
        database.enableWriteAheadLogging()
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
        database.execSQL(
            """
            CREATE TABLE logical_frames (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
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
              UNIQUE(source_id, source_sequence, message_type, envelope_sha256)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE can_observations (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
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
              UNIQUE(source_id, session_id, source_sequence)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE import_receipts (
              bundle_id TEXT NOT NULL,
              manifest_sha256 TEXT NOT NULL,
              imported_at TEXT NOT NULL,
              record_count INTEGER NOT NULL,
              PRIMARY KEY(bundle_id, manifest_sha256)
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX logical_frames_ingested ON logical_frames(ingested_at)")
        database.execSQL("CREATE INDEX can_observations_source_sequence ON can_observations(source_id, source_sequence)")
        createDigitalTwinTables(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var migratedVersion = oldVersion
        if (migratedVersion == 1) {
            createDigitalTwinTables(database)
            migratedVersion = 2
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
        sourceId: String,
        sourceRole: DeviceRole,
        frame: GatewayFrame,
        envelope: ByteArray,
        ingestedAt: Instant = Instant.now(),
    ): Boolean {
        val values = ContentValues().apply {
            put("source_id", sourceId)
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
        sourceId: String,
        observation: CanObservation,
        ingestedAt: Instant = Instant.now(),
    ): Boolean = insertCanObservation(writableDatabase, sourceId, observation, ingestedAt)

    private fun insertCanObservation(
        database: SQLiteDatabase,
        sourceId: String,
        observation: CanObservation,
        ingestedAt: Instant,
    ): Boolean {
        val values = ContentValues().apply {
            put("source_id", sourceId)
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
    fun recentCanObservations(limit: Int = 100_000): List<PersistedCanObservation> {
        require(limit in 1..100_000)
        val observations = mutableListOf<PersistedCanObservation>()
        readableDatabase.query(
            "can_observations",
            arrayOf(
                "source_id", "session_id", "source_sequence", "source_monotonic_us",
                "bitrate_bps", "identifier", "extended", "remote_request", "listen_only",
                "data_length", "data",
            ),
            null,
            null,
            null,
            null,
            "id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dataLength = cursor.getInt(9)
                val data = cursor.getBlob(10)
                require(dataLength in 0..8 && data.size == 8) {
                    "Persisted CAN observation payload shape is invalid."
                }
                observations += PersistedCanObservation(
                    sourceId = cursor.getString(0),
                    observation = CanObservation(
                        sessionId = cursor.getString(1).toUInt(),
                        sourceSequence = cursor.getString(2).toULong(),
                        monotonicMicroseconds = cursor.getString(3).toULong(),
                        bitrateBps = cursor.getInt(4),
                        identifier = cursor.getLong(5).toUInt(),
                        extended = cursor.getInt(6) == 1,
                        remoteRequest = cursor.getInt(7) == 1,
                        listenOnly = cursor.getInt(8) == 1,
                        dataLength = dataLength,
                        data = data,
                    ),
                )
            }
        }
        return observations.asReversed()
    }

    @Synchronized
    fun recentPortableFrames(limit: Int = 20_000): List<PortableEvidenceRecord> {
        require(limit in 1..100_000)
        val records = mutableListOf<PortableEvidenceRecord>()
        readableDatabase.query(
            "logical_frames",
            arrayOf(
                "source_role", "source_id", "source_sequence", "source_monotonic_us",
                "protocol_major", "protocol_minor", "message_type", "flags", "ingested_at",
                "envelope_sha256", "envelope",
            ),
            null, null, null, null, "id DESC", limit.toString(),
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
    fun importBundle(bundle: ImportedEvidenceBundle, importedAt: Instant = Instant.now()): Int {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val receiptExists = database.query(
                "import_receipts", arrayOf("bundle_id"),
                "bundle_id = ? AND manifest_sha256 = ?",
                arrayOf(bundle.manifest.bundleId, bundle.manifestSha256),
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
                val values = ContentValues().apply {
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
                            record.sourceId,
                            observation,
                            evidenceIngestedAt,
                        )
                    }
                }
            }
            database.insertOrThrow("import_receipts", null, ContentValues().apply {
                put("bundle_id", bundle.manifest.bundleId)
                put("manifest_sha256", bundle.manifestSha256)
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

    companion object {
        private const val DATABASE_NAME = "vhos-evidence.db"
        private const val DATABASE_VERSION = 2
        private val gson: Gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .disableHtmlEscaping()
            .create()
    }
}
