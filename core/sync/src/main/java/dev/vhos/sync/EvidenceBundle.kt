package dev.vhos.sync

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import dev.vhos.protocol.GatewayFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
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

data class EvidenceBundleManifest(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val bundleId: String,
    val createdAt: String,
    val creator: BundleCreator,
    val segments: List<BundleSegment>,
) {
    companion object {
        const val CONTRACT = "vhos.evidence-sync-bundle"
        const val CONTRACT_VERSION = "1.0.0"
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
        if (!actual.equals(envelopeSha256, ignoreCase = true)) {
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
)

class BundleException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object EvidenceBundles {
    private const val MANIFEST_PATH = "manifest.json"
    private const val EVIDENCE_PATH = "segments/logical-frames.ndjson"
    private const val MAX_ENTRY_BYTES = 128 * 1024 * 1024
    private const val MAX_RECORDS = 1_000_000
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
        require(records.size <= MAX_RECORDS)
        val ndjson = ByteArrayOutputStream().use { segment ->
            records.forEach { record ->
                record.verifyEnvelope()
                segment.write(gson.toJson(record).toByteArray(Charsets.UTF_8))
                segment.write('\n'.code)
            }
            segment.toByteArray()
        }
        val manifest = EvidenceBundleManifest(
            bundleId = bundleId.toString(),
            createdAt = createdAt.toString(),
            creator = creator,
            segments = listOf(
                BundleSegment(
                    path = EVIDENCE_PATH,
                    mediaType = "application/x-ndjson",
                    sha256 = sha256(ndjson),
                    byteCount = ndjson.size.toLong(),
                    recordCount = records.size.toLong(),
                )
            ),
        )
        val manifestBytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
        ZipOutputStream(output.buffered()).use { zip ->
            putStoredEntry(zip, MANIFEST_PATH, manifestBytes)
            putStoredEntry(zip, EVIDENCE_PATH, ndjson)
        }
        return manifest
    }

    fun read(input: InputStream): ImportedEvidenceBundle {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                validatePath(entry.name)
                if (entry.isDirectory) throw BundleException("Bundle contains an unexpected directory entry.")
                if (entries.containsKey(entry.name)) throw BundleException("Bundle contains duplicate entry ${entry.name}.")
                val bytes = readBounded(zip)
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        val manifestBytes = entries[MANIFEST_PATH]
            ?: throw BundleException("Bundle manifest is missing.")
        val manifest = try {
            gson.fromJson(manifestBytes.toString(Charsets.UTF_8), EvidenceBundleManifest::class.java)
        } catch (error: JsonParseException) {
            throw BundleException("Bundle manifest JSON is invalid.", error)
        }
        validateManifest(manifest)
        val declaredPaths = manifest.segments.map { it.path }.toSet() + MANIFEST_PATH
        if (entries.keys != declaredPaths) throw BundleException("Bundle entries do not exactly match the manifest.")

        val allRecords = mutableListOf<PortableEvidenceRecord>()
        manifest.segments.forEach { segment ->
            val bytes = entries.getValue(segment.path)
            if (bytes.size.toLong() != segment.byteCount) {
                throw BundleException("Segment byte count does not match ${segment.path}.")
            }
            if (!sha256(bytes).equals(segment.sha256, ignoreCase = true)) {
                throw BundleException("Segment SHA-256 does not match ${segment.path}.")
            }
            val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size.toLong() != segment.recordCount) {
                throw BundleException("Segment record count does not match ${segment.path}.")
            }
            if (allRecords.size + lines.size > MAX_RECORDS) throw BundleException("Bundle contains too many records.")
            lines.forEach { line ->
                val record = try {
                    gson.fromJson(line, PortableEvidenceRecord::class.java)
                } catch (error: JsonParseException) {
                    throw BundleException("Evidence NDJSON contains an invalid record.", error)
                }
                if (record.contract != "vhos.portable-logical-frame" || record.contractVersion != "1.0.0") {
                    throw BundleException("Evidence record contract is unsupported.")
                }
                record.verifyEnvelope()
                allRecords += record
            }
        }
        return ImportedEvidenceBundle(
            manifest = manifest,
            manifestSha256 = sha256(manifestBytes),
            records = allRecords,
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

    private fun validateManifest(manifest: EvidenceBundleManifest) {
        if (manifest.contract != EvidenceBundleManifest.CONTRACT ||
            manifest.contractVersion != EvidenceBundleManifest.CONTRACT_VERSION
        ) {
            throw BundleException("Bundle contract is unsupported.")
        }
        try {
            UUID.fromString(manifest.bundleId)
            Instant.parse(manifest.createdAt)
        } catch (error: RuntimeException) {
            throw BundleException("Bundle identity or timestamp is invalid.", error)
        }
        if (manifest.creator.platform !in setOf("ANDROID", "IOS")) {
            throw BundleException("Bundle creator platform is unsupported.")
        }
        if (manifest.segments.isEmpty() || manifest.segments.map { it.path }.toSet().size != manifest.segments.size) {
            throw BundleException("Bundle segment declarations are invalid.")
        }
        manifest.segments.forEach { segment ->
            validatePath(segment.path)
            if (segment.mediaType != "application/x-ndjson") {
                throw BundleException("Bundle segment media type is unsupported.")
            }
            if (!segment.sha256.matches(Regex("^[0-9a-fA-F]{64}$")) ||
                segment.byteCount !in 0..MAX_ENTRY_BYTES.toLong() ||
                segment.recordCount !in 0..MAX_RECORDS.toLong()
            ) {
                throw BundleException("Bundle segment integrity metadata is invalid.")
            }
        }
    }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw BundleException("Unsafe bundle path: $path")
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ENTRY_BYTES) throw BundleException("Bundle entry exceeds the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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
}
