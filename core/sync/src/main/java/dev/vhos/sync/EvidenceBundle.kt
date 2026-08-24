package dev.vhos.sync

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.internal.LazilyParsedNumber
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.vhos.protocol.GatewayFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BundleCreator(
    val platform: String,
    val applicationId: String,
    val applicationVersion: String,
    val deviceModel: String,
)

data class BundleSegment(
    val path: String,
    val mediaType: String,
    val sha256: String,
    val byteCount: Long,
    val recordCount: Long,
)

data class RecoveryEvidenceMetadata(
    val classification: String,
    val vehicleClaimsAuthorized: Boolean,
    val sourceLedgerSha256: String,
) {
    companion object {
        const val CLASSIFICATION = "RECOVERED_PORTABLE_EVIDENCE"
    }
}

data class EvidenceBundleManifest(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val bundleId: String,
    val createdAt: String,
    val creator: BundleCreator,
    val segments: List<BundleSegment>,
    val recovery: RecoveryEvidenceMetadata? = null,
) {
    companion object {
        const val CONTRACT = "vhos.evidence-sync-bundle"
        const val CONTRACT_VERSION_V1 = "1.0.0"
        const val CONTRACT_VERSION_V2 = "2.0.0"
        const val CONTRACT_VERSION = CONTRACT_VERSION_V1
    }
}

data class PortableEvidenceRecord(
    val contract: String = "vhos.portable-logical-frame",
    val contractVersion: String = "1.0.0",
    val sourceRole: String,
    val sourceId: String,
    val sourceSequence: String,
    val sourceMonotonicMicroseconds: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val messageType: Int,
    val flags: Int,
    val ingestedAt: String,
    val envelopeSha256: String,
    val envelopeBase64: String,
) {
    fun envelope(): ByteArray = try {
        Base64.getDecoder().decode(envelopeBase64)
    } catch (error: IllegalArgumentException) {
        throw BundleException("Evidence record contains invalid base64.", error)
    }

    fun verifyEnvelope(): ByteArray {
        val bytes = envelope()
        val actual = EvidenceBundles.sha256(bytes)
        if (actual != envelopeSha256) {
            throw BundleException("Evidence envelope SHA-256 mismatch.")
        }
        val frame = try {
            GatewayFrame.decode(bytes)
        } catch (error: IllegalArgumentException) {
            throw BundleException("Evidence envelope failed VHOS CRC32C or protocol validation.", error)
        }
        if (frame.sequence.toString() != sourceSequence ||
            frame.monotonicMicroseconds.toString() != sourceMonotonicMicroseconds ||
            frame.protocolMajor != protocolMajor || frame.protocolMinor != protocolMinor ||
            frame.messageType.code != messageType || frame.flags != flags
        ) {
            throw BundleException("Evidence record metadata does not match its VHOS envelope.")
        }
        return bytes
    }
}

data class ImportedEvidenceBundle(
    val manifest: EvidenceBundleManifest,
    val manifestSha256: String,
    val records: List<PortableEvidenceRecord>,
    val recoveryMetadata: RecoveryEvidenceMetadata? = null,
) {
    /** Imported evidence is historical input and can never authorize live vehicle claims. */
    val isLiveAuthority: Boolean
        get() = false
}

class BundleException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object EvidenceBundles {
    private data class StreamedSegment(
        val byteCount: Long,
        val sha256: String,
        val records: List<PortableEvidenceRecord>,
    )

    private const val MANIFEST_PATH = "manifest.json"
    private const val EVIDENCE_PATH = "segments/logical-frames.ndjson"
    // Shared iOS/Android/desktop wire profile. Larger histories travel as independently verified
    // bundles; no reader may silently raise these limits or merge generations before verification.
    private const val MAX_ARCHIVE_ENTRIES = 33
    private const val MAX_SEGMENTS = MAX_ARCHIVE_ENTRIES - 1
    private const val MAX_DATA_SEGMENT_BYTES = 16 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_AGGREGATE_UNCOMPRESSED_BYTES = 17 * 1024 * 1024
    private const val MAX_TOTAL_ARCHIVE_BYTES = 18 * 1024 * 1024
    // iOS bounds an individual line through the 16 MiB segment boundary plus the record schema.
    // Keep the streaming reader on that same wire boundary so a schema-valid iOS timestamp with
    // arbitrary RFC 3339 fractional precision is not rejected only by Android.
    private const val MAX_NDJSON_LINE_BYTES = MAX_DATA_SEGMENT_BYTES
    // A portable bundle is an interchange unit, not an unbounded database cursor. This matches the
    // normal export window and bounds the object graph retained while a verified import commits.
    private const val MAX_RECORDS = 20_000
    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .disableHtmlEscaping()
        .create()

    fun write(
        output: OutputStream,
        records: List<PortableEvidenceRecord>,
        creator: BundleCreator,
        bundleId: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): EvidenceBundleManifest {
        if (records.size > MAX_RECORDS) throw BundleException("Bundle contains too many records.")
        val spool = File.createTempFile("vhos-evidence-", ".ndjson")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val checksum = CRC32()
            var segmentBytes = 0L
            FileOutputStream(spool).buffered().use { segment ->
                records.forEach { record ->
                    validatePortableRecord(record)
                    val encoded = gson.toJson(record).toByteArray(Charsets.UTF_8)
                    if (encoded.size > MAX_NDJSON_LINE_BYTES) {
                        throw BundleException("Evidence NDJSON record exceeds its streaming line-size limit.")
                    }
                    val bytesRequired = encoded.size.toLong() + 1L
                    val safeSegmentLimit = MAX_DATA_SEGMENT_BYTES.toLong()
                    if (bytesRequired > safeSegmentLimit - segmentBytes) {
                        throw BundleException(
                            "Bundle export exceeds the data-segment size limit; split evidence into independent bundles."
                        )
                    }
                    segment.write(encoded)
                    segment.write('\n'.code)
                    digest.update(encoded)
                    digest.update('\n'.code.toByte())
                    checksum.update(encoded)
                    checksum.update('\n'.code)
                    segmentBytes += bytesRequired
                }
            }
            val manifest = EvidenceBundleManifest(
                bundleId = bundleId.toString(),
                createdAt = createdAt.toString(),
                creator = creator,
                segments = listOf(
                    BundleSegment(
                        path = EVIDENCE_PATH,
                        mediaType = "application/x-ndjson",
                        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                        byteCount = segmentBytes,
                        recordCount = records.size.toLong(),
                    )
                ),
            )
            validateManifest(manifest, recoveryMetadata = null)
            val manifestBytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
            if (manifestBytes.size > MAX_MANIFEST_BYTES ||
                segmentBytes > MAX_AGGREGATE_UNCOMPRESSED_BYTES.toLong() - manifestBytes.size.toLong()
            ) {
                throw BundleException(
                    "Bundle export exceeds the aggregate uncompressed size limit including its manifest."
                )
            }
            val archiveOutput = ArchiveLimitOutputStream(
                delegate = output,
                maxBytes = MAX_TOTAL_ARCHIVE_BYTES.toLong(),
            )
            ZipOutputStream(archiveOutput.buffered()).use { zip ->
                putStoredEntry(zip, MANIFEST_PATH, manifestBytes)
                putStoredFileEntry(zip, EVIDENCE_PATH, spool, segmentBytes, checksum.value)
            }
            return manifest
        } finally {
            if (!spool.delete()) spool.deleteOnExit()
        }
    }

    fun read(input: InputStream): ImportedEvidenceBundle {
        val archive = input.use(::spoolBoundedArchive)
        return try {
            FileInputStream(archive).buffered().use(::readVerifiedArchive)
        } finally {
            if (!archive.delete()) archive.deleteOnExit()
        }
    }

    private fun readVerifiedArchive(input: InputStream): ImportedEvidenceBundle {
        val entryNames = linkedSetOf<String>()
        val segments = linkedMapOf<String, StreamedSegment>()
        var manifestBytes: ByteArray? = null
        var entryCount = 0
        var aggregateBytes = 0L
        var aggregateRecords = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw BundleException("Bundle contains too many archive entries.")
                }
                validatePath(entry.name)
                if (entry.isDirectory) throw BundleException("Bundle contains an unexpected directory entry.")
                if (!entryNames.add(entry.name)) {
                    throw BundleException("Bundle contains duplicate entry ${entry.name}.")
                }
                val remainingAggregateBytes = MAX_AGGREGATE_UNCOMPRESSED_BYTES.toLong() - aggregateBytes
                val entryLimit = if (entry.name == MANIFEST_PATH) {
                    MAX_MANIFEST_BYTES.toLong()
                } else {
                    MAX_DATA_SEGMENT_BYTES.toLong()
                }
                if (entry.size >= 0 && entry.size > entryLimit) {
                    throw BundleException(
                        if (entry.name == MANIFEST_PATH) {
                            "Bundle manifest exceeds its size limit."
                        } else {
                            "Bundle data segment exceeds its size limit."
                        }
                    )
                }
                if (entry.size >= 0 && entry.size > remainingAggregateBytes) {
                    throw BundleException("Bundle exceeds the aggregate uncompressed size limit.")
                }
                if (entry.name == MANIFEST_PATH) {
                    val bytes = readBoundedEntry(
                        input = zip,
                        maxBytes = minOf(MAX_MANIFEST_BYTES.toLong(), remainingAggregateBytes),
                        limitDescription = "Bundle manifest exceeds its size limit.",
                    )
                    manifestBytes = bytes
                    aggregateBytes += bytes.size.toLong()
                } else {
                    val segmentLimitDescription =
                        if (remainingAggregateBytes < MAX_DATA_SEGMENT_BYTES.toLong()) {
                            "Bundle exceeds the aggregate uncompressed size limit."
                        } else {
                            "Bundle data segment exceeds its size limit."
                        }
                    val streamed = readNdjsonSegment(
                        input = zip,
                        maxBytes = minOf(MAX_DATA_SEGMENT_BYTES.toLong(), remainingAggregateBytes),
                        maxRecords = MAX_RECORDS - aggregateRecords,
                        limitDescription = segmentLimitDescription,
                    )
                    segments[entry.name] = streamed
                    aggregateBytes += streamed.byteCount
                    aggregateRecords += streamed.records.size
                }
                zip.closeEntry()
            }
        }
        val verifiedManifestBytes = manifestBytes
            ?: throw BundleException("Bundle manifest is missing.")
        val manifestJson = parseStrictJsonObject(
            json = decodeUtf8Strict(verifiedManifestBytes, "Bundle manifest"),
            description = "Bundle manifest",
        )
        val recoveryMetadata = validateRecoveryContract(manifestJson)
        val manifest = try {
            gson.fromJson(manifestJson, EvidenceBundleManifest::class.java)
        } catch (error: JsonParseException) {
            throw BundleException("Bundle manifest JSON is invalid.", error)
        }
        validateManifest(manifest, recoveryMetadata)
        val declaredPaths = manifest.segments.map { it.path }.toSet() + MANIFEST_PATH
        if (entryNames != declaredPaths) {
            throw BundleException("Bundle entries do not exactly match the manifest.")
        }

        val allRecords = ArrayList<PortableEvidenceRecord>(aggregateRecords)
        manifest.segments.forEach { segment ->
            val streamed = segments.getValue(segment.path)
            if (streamed.byteCount != segment.byteCount) {
                throw BundleException("Segment byte count does not match ${segment.path}.")
            }
            if (streamed.sha256 != segment.sha256) {
                throw BundleException("Segment SHA-256 does not match ${segment.path}.")
            }
            if (streamed.records.size.toLong() != segment.recordCount) {
                throw BundleException("Segment record count does not match ${segment.path}.")
            }
            allRecords.addAll(streamed.records)
        }
        return ImportedEvidenceBundle(
            manifest = manifest,
            manifestSha256 = sha256(verifiedManifestBytes),
            records = allRecords,
            recoveryMetadata = recoveryMetadata,
        )
    }

    fun toByteArray(
        records: List<PortableEvidenceRecord>,
        creator: BundleCreator,
        bundleId: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): ByteArray = ByteArrayOutputStream().use { output ->
        write(output, records, creator, bundleId, createdAt)
        output.toByteArray()
    }

    fun fromByteArray(bytes: ByteArray): ImportedEvidenceBundle = read(ByteArrayInputStream(bytes))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun validateManifest(
        manifest: EvidenceBundleManifest,
        recoveryMetadata: RecoveryEvidenceMetadata?,
    ) {
        if (manifest.contract != EvidenceBundleManifest.CONTRACT ||
            manifest.contractVersion !in setOf(
                EvidenceBundleManifest.CONTRACT_VERSION_V1,
                EvidenceBundleManifest.CONTRACT_VERSION_V2,
            )
        ) {
            throw BundleException("Bundle contract is unsupported.")
        }
        if (manifest.recovery != recoveryMetadata) {
            throw BundleException("Bundle recovery metadata is invalid.")
        }
        if (!manifest.bundleId.matches(UUID_REGEX)) {
            throw BundleException("Bundle identity is invalid.")
        }
        try {
            UUID.fromString(manifest.bundleId)
        } catch (error: RuntimeException) {
            throw BundleException("Bundle identity is invalid.", error)
        }
        if (!isContractWallTime(manifest.createdAt)) {
            throw BundleException("Bundle timestamp is invalid.")
        }
        validateCreator(manifest.creator)
        if (manifest.segments.isEmpty() || manifest.segments.size > MAX_SEGMENTS ||
            manifest.segments.map { it.path }.toSet().size != manifest.segments.size
        ) {
            throw BundleException("Bundle segment declarations are invalid.")
        }
        var declaredSegmentBytes = 0L
        manifest.segments.forEach { segment ->
            validatePath(segment.path)
            if (segment.mediaType != "application/x-ndjson") {
                throw BundleException("Bundle segment media type is unsupported.")
            }
            if (!segment.sha256.matches(SHA256_REGEX) ||
                segment.byteCount !in 0..MAX_DATA_SEGMENT_BYTES.toLong() ||
                segment.recordCount !in 0..MAX_RECORDS.toLong()
            ) {
                throw BundleException("Bundle segment integrity metadata is invalid.")
            }
            if (segment.byteCount > MAX_AGGREGATE_UNCOMPRESSED_BYTES.toLong() - declaredSegmentBytes) {
                throw BundleException("Bundle declared segment bytes exceed the aggregate size limit.")
            }
            declaredSegmentBytes += segment.byteCount
        }
        if (manifest.contractVersion == EvidenceBundleManifest.CONTRACT_VERSION_V2) {
            val recovery = recoveryMetadata
                ?: throw BundleException("A v2 bundle must declare recovery metadata.")
            if (manifest.segments.size != 1 || manifest.segments.single().path != EVIDENCE_PATH) {
                throw BundleException("A v2 recovery bundle must contain exactly one logical-frame ledger segment.")
            }
            if (manifest.segments.single().sha256 != recovery.sourceLedgerSha256) {
                throw BundleException("A v2 recovery bundle source ledger SHA-256 does not match its ledger segment.")
            }
        }
    }

    private fun validateRecoveryContract(manifestJson: JsonObject): RecoveryEvidenceMetadata? {
        val contractVersion = manifestJson.requiredString("contract_version", "Bundle contract version is missing or invalid.")
        return when (contractVersion) {
            EvidenceBundleManifest.CONTRACT_VERSION_V1 -> {
                if (manifestJson.keySet() != V1_MANIFEST_KEYS) {
                    throw BundleException("A v1 bundle manifest contains missing or unsupported fields.")
                }
                validateSharedManifestShape(manifestJson)
                null
            }

            EvidenceBundleManifest.CONTRACT_VERSION_V2 -> {
                if (manifestJson.keySet() != V2_MANIFEST_KEYS) {
                    throw BundleException("A v2 bundle manifest contains missing or unsupported fields.")
                }
                validateSharedManifestShape(manifestJson)
                val recoveryElement = manifestJson.get("recovery")
                    ?: throw BundleException("A v2 bundle must declare recovery metadata.")
                if (!recoveryElement.isJsonObject) {
                    throw BundleException("A v2 bundle recovery declaration must be an object.")
                }
                val recovery = recoveryElement.asJsonObject
                if (recovery.keySet() != RECOVERY_KEYS) {
                    throw BundleException("A v2 bundle recovery declaration contains missing or unsupported fields.")
                }
                val classification = recovery.requiredString(
                    "classification",
                    "A v2 bundle recovery classification is missing or invalid.",
                )
                if (classification != RecoveryEvidenceMetadata.CLASSIFICATION) {
                    throw BundleException("A v2 bundle recovery classification is unsupported.")
                }
                val authorityElement = recovery.get("vehicle_claims_authorized")
                    ?: throw BundleException("A v2 bundle vehicle authority declaration is missing.")
                if (!authorityElement.isJsonPrimitive ||
                    !authorityElement.asJsonPrimitive.isBoolean ||
                    authorityElement.asBoolean
                ) {
                    throw BundleException("A v2 recovery bundle must set vehicle_claims_authorized to false.")
                }
                val sourceLedgerSha256 = recovery.requiredString(
                    "source_ledger_sha256",
                    "A v2 bundle source ledger SHA-256 is missing or invalid.",
                )
                if (!sourceLedgerSha256.matches(Regex("^[0-9a-f]{64}$"))) {
                    throw BundleException("A v2 bundle source ledger SHA-256 must be 64 lowercase hexadecimal characters.")
                }
                RecoveryEvidenceMetadata(
                    classification = classification,
                    vehicleClaimsAuthorized = false,
                    sourceLedgerSha256 = sourceLedgerSha256,
                )
            }

            else -> throw BundleException("Bundle contract is unsupported.")
        }
    }

    private fun validateSharedManifestShape(manifestJson: JsonObject) {
        manifestJson.requiredString("contract", "Bundle contract is missing or invalid.")
        manifestJson.requiredString("bundle_id", "Bundle ID is missing or invalid.")
        manifestJson.requiredString("created_at", "Bundle creation time is missing or invalid.")
        val creatorElement = manifestJson.get("creator")
            ?: throw BundleException("Bundle creator is missing or invalid.")
        if (!creatorElement.isJsonObject || creatorElement.asJsonObject.keySet() != CREATOR_KEYS) {
            throw BundleException("Bundle creator contains missing or unsupported fields.")
        }
        creatorElement.asJsonObject.apply {
            requiredString("platform", "Bundle creator platform is missing or invalid.")
            requiredString("application_id", "Bundle creator application ID is missing or invalid.")
            requiredString("application_version", "Bundle creator application version is missing or invalid.")
            requiredString("device_model", "Bundle creator device model is missing or invalid.")
        }

        val segmentsElement = manifestJson.get("segments")
            ?: throw BundleException("Bundle segments are missing or invalid.")
        if (!segmentsElement.isJsonArray || segmentsElement.asJsonArray.any { segment ->
                !segment.isJsonObject || segment.asJsonObject.keySet() != SEGMENT_KEYS
            }
        ) {
            throw BundleException("A bundle segment contains missing or unsupported fields.")
        }
        segmentsElement.asJsonArray.forEach { segmentElement ->
            segmentElement.asJsonObject.apply {
                requiredString("path", "Bundle segment path is missing or invalid.")
                requiredString("media_type", "Bundle segment media type is missing or invalid.")
                requiredString("sha256", "Bundle segment SHA-256 is missing or invalid.")
                requiredInteger("byte_count", "Bundle segment byte count is missing or invalid.")
                requiredInteger("record_count", "Bundle segment record count is missing or invalid.")
            }
        }
    }

    private fun JsonObject.requiredString(name: String, message: String): String {
        val element = get(name) ?: throw BundleException(message)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) throw BundleException(message)
        return element.asString
    }

    private fun JsonObject.requiredInteger(name: String, message: String): Long {
        val element = get(name) ?: throw BundleException(message)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) throw BundleException(message)
        val value = try {
            BigDecimal(element.asString)
        } catch (error: NumberFormatException) {
            throw BundleException(message, error)
        }
        if (value.stripTrailingZeros().scale() > 0 || value < BigDecimal.valueOf(Long.MIN_VALUE) ||
            value > BigDecimal.valueOf(Long.MAX_VALUE)
        ) {
            throw BundleException(message)
        }
        return value.toLong()
    }

    private fun validateCreator(creator: BundleCreator) {
        if (creator.platform !in setOf("ANDROID", "IOS")) {
            throw BundleException("Bundle creator platform is unsupported.")
        }
        requireSchemaStringLength(creator.applicationId, 1, 160, "Bundle creator application ID")
        requireSchemaStringLength(creator.applicationVersion, 1, 80, "Bundle creator application version")
        requireSchemaStringLength(creator.deviceModel, 1, 160, "Bundle creator device model")
    }

    private fun validatePortableRecordShape(record: JsonObject) {
        record.requiredString("contract", "Evidence record contract is missing or invalid.")
        record.requiredString("contract_version", "Evidence record contract version is missing or invalid.")
        record.requiredString("source_role", "Evidence record source role is missing or invalid.")
        record.requiredString("source_id", "Evidence record source ID is missing or invalid.")
        record.requiredString("source_sequence", "Evidence record source sequence is missing or invalid.")
        record.requiredString(
            "source_monotonic_microseconds",
            "Evidence record monotonic time is missing or invalid.",
        )
        record.requiredIntegerInRange(
            "protocol_major",
            0L..255L,
            "Evidence record protocol major is missing, invalid, or out of range.",
        )
        record.requiredIntegerInRange(
            "protocol_minor",
            0L..255L,
            "Evidence record protocol minor is missing, invalid, or out of range.",
        )
        record.requiredIntegerInRange(
            "message_type",
            1L..255L,
            "Evidence record message type is missing, invalid, or out of range.",
        )
        record.requiredIntegerInRange(
            "flags",
            0L..255L,
            "Evidence record flags are missing, invalid, or out of range.",
        )
        record.requiredString("ingested_at", "Evidence record ingestion time is missing or invalid.")
        record.requiredString("envelope_sha256", "Evidence record envelope SHA-256 is missing or invalid.")
        record.requiredString("envelope_base64", "Evidence record envelope base64 is missing or invalid.")
    }

    private fun parsePortableRecord(line: String): PortableEvidenceRecord {
        val recordJson = parseStrictJsonObject(line, "Evidence NDJSON record")
        if (recordJson.keySet() != PORTABLE_RECORD_KEYS) {
            throw BundleException("Evidence NDJSON record contains missing or unsupported fields.")
        }
        validatePortableRecordShape(recordJson)
        val record = try {
            gson.fromJson(recordJson, PortableEvidenceRecord::class.java)
        } catch (error: JsonParseException) {
            throw BundleException("Evidence NDJSON contains an invalid record.", error)
        }
        validatePortableRecord(record)
        return record
    }

    private fun validatePortableRecord(record: PortableEvidenceRecord) {
        if (record.contract != "vhos.portable-logical-frame" || record.contractVersion != "1.0.0") {
            throw BundleException("Evidence record contract is unsupported.")
        }
        if (record.sourceRole !in setOf("OBD_CAN", "AC_SENSOR")) {
            throw BundleException("Evidence record source role is unsupported.")
        }
        requireSchemaStringLength(record.sourceId, 1, 160, "Evidence record source ID")
        if (!record.sourceSequence.matches(UNSIGNED_DECIMAL_REGEX) || record.sourceSequence.length > 20 ||
            !record.sourceMonotonicMicroseconds.matches(UNSIGNED_DECIMAL_REGEX) ||
            record.sourceMonotonicMicroseconds.length > 20
        ) {
            throw BundleException("Evidence record source clock values are not canonical unsigned decimals.")
        }
        if (record.protocolMajor !in 0..255 || record.protocolMinor !in 0..255 ||
            record.messageType !in 1..255 || record.flags !in 0..255
        ) {
            throw BundleException("Evidence record protocol metadata is out of range.")
        }
        if (!isContractWallTime(record.ingestedAt)) {
            throw BundleException("Evidence record ingestion timestamp is invalid.")
        }
        if (!record.envelopeSha256.matches(SHA256_REGEX)) {
            throw BundleException("Evidence record envelope SHA-256 must be lowercase hexadecimal.")
        }
        if (record.envelopeBase64.length > MAX_ENVELOPE_BASE64_LENGTH) {
            throw BundleException("Evidence record envelope base64 exceeds the schema limit.")
        }
        record.verifyEnvelope()
    }

    private fun JsonObject.requiredIntegerInRange(
        name: String,
        range: LongRange,
        message: String,
    ): Long = requiredInteger(name, message).also { value ->
        if (value !in range) throw BundleException(message)
    }

    private fun requireSchemaStringLength(value: String, min: Int, max: Int, description: String) {
        val codePointLength = value.codePointCount(0, value.length)
        if (codePointLength !in min..max) {
            throw BundleException("$description length is outside the schema bounds.")
        }
    }

    private fun parseStrictJsonObject(json: String, description: String): JsonObject = try {
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val element = readJsonElementRejectingDuplicateKeys(reader, description, depth = 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw BundleException("$description JSON contains trailing content.")
            }
            if (!element.isJsonObject) throw BundleException("$description JSON must be an object.")
            element.asJsonObject
        }
    } catch (error: BundleException) {
        throw error
    } catch (error: IOException) {
        throw BundleException("$description JSON is invalid.", error)
    } catch (error: IllegalStateException) {
        throw BundleException("$description JSON is invalid.", error)
    }

    private fun readJsonElementRejectingDuplicateKeys(
        reader: JsonReader,
        description: String,
        depth: Int,
    ): JsonElement {
        if (depth > MAX_JSON_DEPTH) {
            throw BundleException("$description exceeds the JSON nesting limit.")
        }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { jsonObject ->
                val names = mutableSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    if (names.size >= MAX_JSON_COLLECTION_MEMBERS) {
                        throw BundleException("$description exceeds the JSON object-member limit.")
                    }
                    val name = reader.nextName()
                    if (!names.add(name)) {
                        throw BundleException("$description contains duplicate JSON key '$name'.")
                    }
                    jsonObject.add(
                        name,
                        readJsonElementRejectingDuplicateKeys(reader, description, depth + 1),
                    )
                }
                reader.endObject()
            }

            JsonToken.BEGIN_ARRAY -> JsonArray().also { jsonArray ->
                reader.beginArray()
                while (reader.hasNext()) {
                    if (jsonArray.size() >= MAX_JSON_COLLECTION_MEMBERS) {
                        throw BundleException("$description exceeds the JSON array-member limit.")
                    }
                    jsonArray.add(readJsonElementRejectingDuplicateKeys(reader, description, depth + 1))
                }
                reader.endArray()
            }

            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(LazilyParsedNumber(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }

            else -> throw BundleException("$description JSON is invalid.")
        }
    }

    private fun validatePath(path: String) {
        if (path.length > 256 || !path.matches(SEGMENT_PATH_REGEX) || path.isBlank() ||
            path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw BundleException("Unsafe bundle path: $path")
        }
    }

    private fun spoolBoundedArchive(input: InputStream): File {
        val archive = File.createTempFile("vhos-evidence-import-", ".vhossync")
        try {
            FileOutputStream(archive).buffered().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count.toLong() > MAX_TOTAL_ARCHIVE_BYTES.toLong() - total) {
                        throw BundleException("Bundle exceeds the total archive size limit.")
                    }
                    output.write(buffer, 0, count)
                    total += count.toLong()
                }
            }
            return archive
        } catch (error: Exception) {
            if (!archive.delete()) archive.deleteOnExit()
            throw error
        }
    }

    private class ArchiveLimitOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long,
    ) : OutputStream() {
        private var bytesWritten = 0L

        override fun write(value: Int) {
            requireCapacity(1)
            delegate.write(value)
            bytesWritten++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            requireCapacity(length)
            delegate.write(bytes, offset, length)
            bytesWritten += length.toLong()
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun requireCapacity(length: Int) {
            if (length.toLong() > maxBytes - bytesWritten) {
                throw BundleException("Bundle export exceeds the total archive size limit.")
            }
        }
    }

    private fun readBoundedEntry(
        input: InputStream,
        maxBytes: Long,
        limitDescription: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > maxBytes - total) {
                throw BundleException(limitDescription)
            }
            total += count.toLong()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readNdjsonSegment(
        input: InputStream,
        maxBytes: Long,
        maxRecords: Int,
        limitDescription: String,
    ): StreamedSegment {
        val digest = MessageDigest.getInstance("SHA-256")
        val records = mutableListOf<PortableEvidenceRecord>()
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L

        fun appendLineBytes(bytes: ByteArray, offset: Int, count: Int) {
            if (count > MAX_NDJSON_LINE_BYTES - line.size()) {
                throw BundleException("Evidence NDJSON record exceeds its streaming line-size limit.")
            }
            line.write(bytes, offset, count)
        }

        fun finishLine() {
            val bytes = line.toByteArray().let { raw ->
                if (raw.lastOrNull() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
            }
            line.reset()
            if (bytes.isEmpty()) return
            val decoded = decodeUtf8Strict(bytes, "Evidence NDJSON record")
            if (records.size >= maxRecords) throw BundleException("Bundle contains too many records.")
            records += parsePortableRecord(decoded)
        }

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > maxBytes - total) {
                throw BundleException(limitDescription)
            }
            total += count.toLong()
            digest.update(buffer, 0, count)
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    appendLineBytes(buffer, start, index - start)
                    finishLine()
                    start = index + 1
                }
            }
            appendLineBytes(buffer, start, count - start)
        }
        if (line.size() > 0) finishLine()
        return StreamedSegment(
            byteCount = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            records = records,
        )
    }

    private fun decodeUtf8Strict(bytes: ByteArray, description: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw BundleException("$description is not valid UTF-8.", error)
    }

    /**
     * Match `common.schema.json#/$defs/wallTime` and the iOS scalar validator exactly.
     *
     * `Instant.parse` is intentionally not used here: it rejects schema-valid RFC 3339 values
     * with more than nanosecond precision and offsets outside `Instant`'s narrower parser policy.
     * The versioned interchange contract validates the lexical time/offset shape and the actual
     * Gregorian calendar date independently, while rejecting leap-second spellings.
     */
    private fun isContractWallTime(value: String): Boolean {
        if (!value.matches(WALL_TIME_REGEX)) return false
        return try {
            LocalDate.of(
                value.substring(0, 4).toInt(),
                value.substring(5, 7).toInt(),
                value.substring(8, 10).toInt(),
            )
            true
        } catch (_: DateTimeException) {
            false
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun putStoredEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val checksum = CRC32().apply { update(bytes) }.value
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            time = 0
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = checksum
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putStoredFileEntry(
        zip: ZipOutputStream,
        path: String,
        file: File,
        byteCount: Long,
        checksum: Long,
    ) {
        require(file.length() == byteCount) { "Evidence spool length changed before archive emission." }
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            time = 0
            size = byteCount
            compressedSize = byteCount
            crc = checksum
        }
        zip.putNextEntry(entry)
        FileInputStream(file).buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private val V1_MANIFEST_KEYS = setOf(
        "contract",
        "contract_version",
        "bundle_id",
        "created_at",
        "creator",
        "segments",
    )
    private val V2_MANIFEST_KEYS = V1_MANIFEST_KEYS + "recovery"
    private val RECOVERY_KEYS = setOf(
        "classification",
        "vehicle_claims_authorized",
        "source_ledger_sha256",
    )
    private val CREATOR_KEYS = setOf(
        "platform",
        "application_id",
        "application_version",
        "device_model",
    )
    private val SEGMENT_KEYS = setOf(
        "path",
        "media_type",
        "sha256",
        "byte_count",
        "record_count",
    )
    private val PORTABLE_RECORD_KEYS = setOf(
        "contract",
        "contract_version",
        "source_role",
        "source_id",
        "source_sequence",
        "source_monotonic_microseconds",
        "protocol_major",
        "protocol_minor",
        "message_type",
        "flags",
        "ingested_at",
        "envelope_sha256",
        "envelope_base64",
    )
    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val UNSIGNED_DECIMAL_REGEX = Regex("^(0|[1-9][0-9]*)$")
    private val SEGMENT_PATH_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")
    private val WALL_TIME_REGEX = Regex(
        "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])" +
            "[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]" +
            "(\\.[0-9]+)?([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$"
    )
    private const val MAX_ENVELOPE_BASE64_LENGTH = 2_097_152
    private const val MAX_JSON_DEPTH = 16
    private const val MAX_JSON_COLLECTION_MEMBERS = 64
}
