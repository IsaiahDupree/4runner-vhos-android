package dev.vhos.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.vhos.model.DeviceRole
import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.decodeCanObservations
import dev.vhos.sync.EvidenceBundles
import dev.vhos.sync.ImportedEvidenceBundle
import dev.vhos.sync.PortableEvidenceRecord
import java.time.Instant
import java.util.Base64

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
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Evidence database migration $oldVersion -> $newVersion is not implemented; destructive migration is forbidden."
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
        private const val DATABASE_VERSION = 1
    }
}
