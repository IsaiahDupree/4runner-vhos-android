package dev.vhos.maintenance

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, deterministic maintenance-ledger archive.
 *
 * The caller owns the canonical JSON representation of [payload]. This codec deliberately treats
 * it as opaque evidence and preserves the bytes exactly. Attachments are addressed only by their
 * SHA-256, so a filename cannot become an identity or an archive path.
 *
 * Every ZIP entry is declared by the canonical manifest with a byte length and SHA-256. The
 * manifest declares itself using a precisely defined scope: its hash is calculated over the
 * canonical manifest after replacing the manifest entry's hash with 64 zeroes. This avoids a
 * recursive hash while still detecting accidental or partial manifest mutation. A release or sync
 * envelope should additionally pin the [MaintenanceArchiveReceipt.archiveSha256] when authenticity
 * across a trust boundary is required.
 *
 * Only STORED ZIP entries are accepted. This makes output byte-for-byte deterministic for equal
 * inputs and excludes compressed ZIP bombs. Reads still enforce independent archive, entry, and
 * total-uncompressed limits before returning any content to persistence.
 */
object MaintenanceArchiveCodec {
    const val ARCHIVE_FORMAT = "vhos.maintenance-archive"
    const val FORMAT_VERSION = 1
    const val MANIFEST_PATH = "manifest.json"
    const val PAYLOAD_PATH = "payload/maintenance.json"

    private const val MANIFEST_MEDIA_TYPE =
        "application/vnd.vhos.maintenance-archive-manifest+json"
    private const val PAYLOAD_MEDIA_TYPE = "application/json"
    private const val MANIFEST_HASH_SCOPE = "CANONICAL_MANIFEST_WITH_SELF_SHA256_ZEROED"
    private const val ENTRY_HASH_SCOPE = "ENTRY_BYTES"
    private const val ZERO_SHA256 =
        "0000000000000000000000000000000000000000000000000000000000000000"
    private const val ATTACHMENT_PREFIX = "attachments/sha256/"

    private const val HARD_MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val HARD_MAX_MANIFEST_BYTES = 1024L * 1024L
    private const val HARD_MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L
    private const val HARD_MAX_ATTACHMENT_BYTES = 64L * 1024L * 1024L
    private const val HARD_MAX_TOTAL_CONTENT_BYTES = 256L * 1024L * 1024L
    private const val HARD_MAX_ENTRY_COUNT = 1024
    private const val HARD_MAX_PATH_LENGTH = 240

    data class Limits(
        val maxArchiveBytes: Long = HARD_MAX_ARCHIVE_BYTES,
        val maxManifestBytes: Long = HARD_MAX_MANIFEST_BYTES,
        val maxPayloadBytes: Long = HARD_MAX_PAYLOAD_BYTES,
        val maxAttachmentBytes: Long = HARD_MAX_ATTACHMENT_BYTES,
        val maxTotalContentBytes: Long = HARD_MAX_TOTAL_CONTENT_BYTES,
        val maxEntryCount: Int = HARD_MAX_ENTRY_COUNT,
        val maxPathLength: Int = HARD_MAX_PATH_LENGTH,
    ) {
        internal fun validated(): Limits = apply {
            archiveCheck(maxArchiveBytes in 1..HARD_MAX_ARCHIVE_BYTES) {
                "maxArchiveBytes must be within the hard archive ceiling."
            }
            archiveCheck(maxManifestBytes in 1..HARD_MAX_MANIFEST_BYTES) {
                "maxManifestBytes must be within the hard manifest ceiling."
            }
            archiveCheck(maxPayloadBytes in 1..HARD_MAX_PAYLOAD_BYTES) {
                "maxPayloadBytes must be within the hard payload ceiling."
            }
            archiveCheck(maxAttachmentBytes in 1..HARD_MAX_ATTACHMENT_BYTES) {
                "maxAttachmentBytes must be within the hard attachment ceiling."
            }
            archiveCheck(maxTotalContentBytes in 1..HARD_MAX_TOTAL_CONTENT_BYTES) {
                "maxTotalContentBytes must be within the hard content ceiling."
            }
            archiveCheck(maxEntryCount in 2..HARD_MAX_ENTRY_COUNT) {
                "maxEntryCount must allow the manifest and payload and remain under the ceiling."
            }
            archiveCheck(maxPathLength in PAYLOAD_PATH.length..HARD_MAX_PATH_LENGTH) {
                "maxPathLength must allow required paths and remain under the ceiling."
            }
        }
    }

    /** Input attachment. [bytes] are copied before archive construction. */
    class Attachment(
        bytes: ByteArray,
        val mediaType: String = "application/octet-stream",
    ) {
        private val body = bytes.copyOf()
        internal fun bodyCopy(): ByteArray = body.copyOf()
    }

    /** Verified content-addressed attachment returned from an archive read. */
    class VerifiedAttachment internal constructor(
        val sha256: String,
        val mediaType: String,
        bytes: ByteArray,
    ) {
        private val body = bytes.copyOf()
        val length: Long get() = body.size.toLong()
        fun bytes(): ByteArray = body.copyOf()
    }

    /** Fully verified archive content. Arrays are copied at the API boundary. */
    class Contents internal constructor(
        val payloadSchema: String,
        payload: ByteArray,
        attachments: List<VerifiedAttachment>,
        val manifestSha256: String,
    ) {
        private val payloadBody = payload.copyOf()
        private val attachmentBodies = attachments.toList()

        fun payload(): ByteArray = payloadBody.copyOf()
        fun attachments(): List<VerifiedAttachment> = attachmentBodies.toList()
    }

    data class MaintenanceArchiveReceipt(
        val archiveFormat: String,
        val formatVersion: Int,
        val archiveSha256: String,
        val archiveLength: Long,
        val manifestSha256: String,
        val payloadSha256: String,
        val attachmentSha256: List<String>,
    )

    /** Convenience API for a bounded, in-memory archive. */
    fun encode(
        payload: ByteArray,
        payloadSchema: String,
        attachments: List<Attachment> = emptyList(),
        limits: Limits = Limits(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        write(output, payload, payloadSchema, attachments, limits)
        return output.toByteArray()
    }

    /** Writes a complete archive without closing [output]. */
    fun write(
        output: OutputStream,
        payload: ByteArray,
        payloadSchema: String,
        attachments: List<Attachment> = emptyList(),
        limits: Limits = Limits(),
    ): MaintenanceArchiveReceipt {
        val checkedLimits = limits.validated()
        validatePayloadSchema(payloadSchema)
        archiveCheck(payload.isNotEmpty()) { "Maintenance payload must not be empty." }
        archiveCheck(payload.size.toLong() <= checkedLimits.maxPayloadBytes) {
            "Maintenance payload exceeds the configured byte limit."
        }
        archiveCheck(attachments.size + 2 <= checkedLimits.maxEntryCount) {
            "Archive entry count exceeds the configured limit."
        }

        val payloadCopy = payload.copyOf()
        val payloadDigest = sha256(payloadCopy)
        val preparedAttachments = attachments.map { attachment ->
            val body = attachment.bodyCopy()
            validateMediaType(attachment.mediaType)
            archiveCheck(body.isNotEmpty()) { "Attachment blobs must not be empty." }
            archiveCheck(body.size.toLong() <= checkedLimits.maxAttachmentBytes) {
                "Attachment exceeds the configured per-blob byte limit."
            }
            PreparedAttachment(sha256(body), attachment.mediaType, body)
        }
        archiveCheck(preparedAttachments.map { it.sha256 }.distinct().size == preparedAttachments.size) {
            "Duplicate content-addressed attachment supplied."
        }

        val attachmentBytes = preparedAttachments.sumOf { it.bytes.size.toLong() }
        archiveCheck(payloadCopy.size.toLong() + attachmentBytes <= checkedLimits.maxTotalContentBytes) {
            "Payload and attachments exceed the configured content byte limit."
        }

        val entriesWithoutManifest = buildList {
            add(
                ManifestEntry(
                    kind = EntryKind.PAYLOAD,
                    path = PAYLOAD_PATH,
                    mediaType = PAYLOAD_MEDIA_TYPE,
                    length = payloadCopy.size.toLong(),
                    sha256 = payloadDigest,
                    sha256Scope = ENTRY_HASH_SCOPE,
                )
            )
            preparedAttachments.sortedBy { it.sha256 }.forEach { attachment ->
                val path = "$ATTACHMENT_PREFIX${attachment.sha256}"
                validatePath(path, checkedLimits)
                add(
                    ManifestEntry(
                        kind = EntryKind.ATTACHMENT,
                        path = path,
                        mediaType = attachment.mediaType,
                        length = attachment.bytes.size.toLong(),
                        sha256 = attachment.sha256,
                        sha256Scope = ENTRY_HASH_SCOPE,
                    )
                )
            }
        }
        val manifest = buildManifest(payloadSchema, entriesWithoutManifest, checkedLimits)
        val manifestBytes = renderManifest(manifest).toByteArray(StandardCharsets.UTF_8)
        archiveCheck(manifestBytes.size.toLong() <= checkedLimits.maxManifestBytes) {
            "Manifest exceeds the configured byte limit."
        }

        val rawOutput = DigestCountingOutputStream(output, checkedLimits.maxArchiveBytes)
        try {
            val zip = ZipOutputStream(rawOutput, StandardCharsets.UTF_8)
            writeStoredEntry(zip, MANIFEST_PATH, manifestBytes)
            writeStoredEntry(zip, PAYLOAD_PATH, payloadCopy)
            preparedAttachments.sortedBy { it.sha256 }.forEach { attachment ->
                writeStoredEntry(zip, "$ATTACHMENT_PREFIX${attachment.sha256}", attachment.bytes)
            }
            zip.finish()
            zip.flush()
        } catch (error: MaintenanceArchiveException) {
            throw error
        } catch (error: IOException) {
            throw MaintenanceArchiveException("Unable to write maintenance archive.", error)
        }

        return MaintenanceArchiveReceipt(
            archiveFormat = ARCHIVE_FORMAT,
            formatVersion = FORMAT_VERSION,
            archiveSha256 = rawOutput.digestHex(),
            archiveLength = rawOutput.count,
            manifestSha256 = sha256(manifestBytes),
            payloadSha256 = payloadDigest,
            attachmentSha256 = preparedAttachments.map { it.sha256 }.sorted(),
        )
    }

    /**
     * Reads and verifies an archive without closing [input]. Use [decode] with
     * [expectedArchiveSha256] when an external envelope pins the whole-archive digest.
     */
    fun read(input: InputStream, limits: Limits = Limits()): Contents {
        val checkedLimits = limits.validated()
        val boundedInput = BoundedInputStream(input, checkedLimits.maxArchiveBytes)
        val zip = ZipInputStream(boundedInput, StandardCharsets.UTF_8)
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                archiveCheck(entries.size < checkedLimits.maxEntryCount) {
                    "Archive entry count exceeds the configured limit."
                }
                archiveCheck(!entry.isDirectory) { "Directory ZIP entries are not permitted." }
                archiveCheck(entry.method == ZipEntry.STORED) {
                    "Compressed ZIP entries are not permitted."
                }
                validatePath(entry.name, checkedLimits)
                archiveCheck(!entries.containsKey(entry.name)) {
                    "Duplicate ZIP entry path: ${entry.name}"
                }
                if (entries.isEmpty()) {
                    archiveCheck(entry.name == MANIFEST_PATH) {
                        "The canonical manifest must be the first ZIP entry."
                    }
                }
                val entryLimit = when (entry.name) {
                    MANIFEST_PATH -> checkedLimits.maxManifestBytes
                    PAYLOAD_PATH -> checkedLimits.maxPayloadBytes
                    else -> checkedLimits.maxAttachmentBytes
                }
                archiveCheck(entry.size >= 0L && entry.size <= entryLimit) {
                    "ZIP entry declares an invalid or excessive byte length: ${entry.name}"
                }
                archiveCheck(entry.compressedSize == entry.size) {
                    "Stored ZIP entry length is inconsistent: ${entry.name}"
                }
                entries[entry.name] = readEntry(zip, entryLimit, entry.name)
                zip.closeEntry()
            }
        } catch (error: MaintenanceArchiveException) {
            throw error
        } catch (error: ZipException) {
            throw MaintenanceArchiveException("Malformed or corrupted maintenance archive.", error)
        } catch (error: IOException) {
            throw MaintenanceArchiveException("Unable to read maintenance archive.", error)
        }

        archiveCheck(entries.isNotEmpty()) { "Maintenance archive is empty." }
        archiveCheck(entries.containsKey(MANIFEST_PATH)) { "Maintenance archive has no manifest." }
        val manifestBytes = requireNotNull(entries[MANIFEST_PATH])
        val manifest = parseManifest(manifestBytes)
        validateManifest(manifest, manifestBytes, checkedLimits)

        val expectedOrder = manifest.entries.map { it.path }
        archiveCheck(entries.keys.toList() == expectedOrder) {
            "ZIP entries are missing, unlisted, duplicated, or not in canonical order."
        }
        archiveCheck(entries.keys == expectedOrder.toSet()) {
            "ZIP entries do not exactly match the manifest."
        }

        var totalContentBytes = 0L
        manifest.entries.forEach { declared ->
            val bytes = entries[declared.path]
                ?: throw MaintenanceArchiveException("Missing declared ZIP entry: ${declared.path}")
            archiveCheck(bytes.size.toLong() == declared.length) {
                "ZIP entry length does not match the manifest: ${declared.path}"
            }
            when (declared.kind) {
                EntryKind.MANIFEST -> verifyManifestSelfHash(manifest, declared)
                EntryKind.PAYLOAD,
                EntryKind.ATTACHMENT,
                -> archiveCheck(sha256(bytes) == declared.sha256) {
                    "ZIP entry SHA-256 does not match the manifest: ${declared.path}"
                }
            }
            if (declared.kind != EntryKind.MANIFEST) {
                totalContentBytes = safeAdd(totalContentBytes, declared.length)
                archiveCheck(totalContentBytes <= checkedLimits.maxTotalContentBytes) {
                    "Archive content exceeds the configured total byte limit."
                }
            }
        }

        val payloadEntry = manifest.entries.single { it.kind == EntryKind.PAYLOAD }
        val verifiedAttachments = manifest.entries
            .filter { it.kind == EntryKind.ATTACHMENT }
            .map { declared ->
                VerifiedAttachment(
                    sha256 = declared.sha256,
                    mediaType = declared.mediaType,
                    bytes = requireNotNull(entries[declared.path]),
                )
            }
        return Contents(
            payloadSchema = manifest.payloadSchema,
            payload = requireNotNull(entries[payloadEntry.path]),
            attachments = verifiedAttachments,
            manifestSha256 = sha256(manifestBytes),
        )
    }

    /** Verifies an optional whole-archive digest before parsing any ZIP structure. */
    fun decode(
        archive: ByteArray,
        expectedArchiveSha256: String? = null,
        limits: Limits = Limits(),
    ): Contents {
        val checkedLimits = limits.validated()
        archiveCheck(archive.isNotEmpty()) { "Maintenance archive must not be empty." }
        archiveCheck(archive.size.toLong() <= checkedLimits.maxArchiveBytes) {
            "Maintenance archive exceeds the configured byte limit."
        }
        expectedArchiveSha256?.let {
            validateSha256(it, "expected archive SHA-256")
            archiveCheck(sha256(archive) == it) { "Whole-archive SHA-256 does not match." }
        }
        return read(ByteArrayInputStream(archive), checkedLimits)
    }

    private fun buildManifest(
        payloadSchema: String,
        entriesWithoutManifest: List<ManifestEntry>,
        limits: Limits,
    ): Manifest {
        var manifestLength = 0L
        var manifest: Manifest
        repeat(8) {
            val manifestEntry = ManifestEntry(
                kind = EntryKind.MANIFEST,
                path = MANIFEST_PATH,
                mediaType = MANIFEST_MEDIA_TYPE,
                length = manifestLength,
                sha256 = ZERO_SHA256,
                sha256Scope = MANIFEST_HASH_SCOPE,
            )
            manifest = Manifest(payloadSchema, listOf(manifestEntry) + entriesWithoutManifest)
            val nextLength = renderManifest(manifest).toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (nextLength == manifestLength) {
                val selfHash = sha256(renderManifest(manifest).toByteArray(StandardCharsets.UTF_8))
                val completed = manifest.copy(
                    entries = listOf(manifestEntry.copy(sha256 = selfHash)) + entriesWithoutManifest
                )
                archiveCheck(
                    renderManifest(completed).toByteArray(StandardCharsets.UTF_8).size.toLong() == manifestLength
                ) { "Manifest byte length did not converge." }
                validateManifestShape(completed, limits)
                return completed
            }
            manifestLength = nextLength
        }
        throw MaintenanceArchiveException("Manifest byte length did not converge.")
    }

    private fun validateManifest(manifest: Manifest, rawBytes: ByteArray, limits: Limits) {
        validateManifestShape(manifest, limits)
        archiveCheck(rawBytes.size.toLong() <= limits.maxManifestBytes) {
            "Manifest exceeds the configured byte limit."
        }
        val canonical = renderManifest(manifest).toByteArray(StandardCharsets.UTF_8)
        archiveCheck(rawBytes.contentEquals(canonical)) {
            "Manifest is not in canonical JSON form."
        }
        val manifestEntry = manifest.entries.single { it.kind == EntryKind.MANIFEST }
        archiveCheck(manifestEntry.length == rawBytes.size.toLong()) {
            "Manifest does not declare its exact byte length."
        }
    }

    private fun validateManifestShape(manifest: Manifest, limits: Limits) {
        validatePayloadSchema(manifest.payloadSchema)
        archiveCheck(manifest.entries.size in 2..limits.maxEntryCount) {
            "Manifest entry count is invalid."
        }
        archiveCheck(manifest.entries.map { it.path }.distinct().size == manifest.entries.size) {
            "Manifest contains duplicate paths."
        }
        archiveCheck(manifest.entries.first().kind == EntryKind.MANIFEST) {
            "Manifest entry must be first."
        }
        archiveCheck(manifest.entries.count { it.kind == EntryKind.MANIFEST } == 1) {
            "Manifest must declare itself exactly once."
        }
        archiveCheck(manifest.entries.count { it.kind == EntryKind.PAYLOAD } == 1) {
            "Manifest must declare one maintenance payload."
        }
        archiveCheck(manifest.entries.first().path == MANIFEST_PATH) {
            "Manifest path is not canonical."
        }
        val payload = manifest.entries.single { it.kind == EntryKind.PAYLOAD }
        archiveCheck(payload.path == PAYLOAD_PATH) { "Payload path is not canonical." }
        archiveCheck(payload.mediaType == PAYLOAD_MEDIA_TYPE) { "Payload media type is invalid." }
        archiveCheck(payload.sha256Scope == ENTRY_HASH_SCOPE) { "Payload hash scope is invalid." }
        archiveCheck(payload.length in 1..limits.maxPayloadBytes) {
            "Payload byte length is invalid."
        }

        val manifestEntry = manifest.entries.first()
        archiveCheck(manifestEntry.mediaType == MANIFEST_MEDIA_TYPE) {
            "Manifest media type is invalid."
        }
        archiveCheck(manifestEntry.sha256Scope == MANIFEST_HASH_SCOPE) {
            "Manifest hash scope is invalid."
        }
        archiveCheck(manifestEntry.length in 1..limits.maxManifestBytes) {
            "Manifest byte length is invalid."
        }

        val attachmentPaths = manifest.entries
            .filter { it.kind == EntryKind.ATTACHMENT }
            .map { it.path }
        archiveCheck(attachmentPaths == attachmentPaths.sorted()) {
            "Attachment entries are not in canonical digest order."
        }
        manifest.entries.forEach { entry ->
            validatePath(entry.path, limits)
            validateSha256(entry.sha256, "entry SHA-256")
            archiveCheck(entry.length > 0L) { "ZIP entry lengths must be positive." }
            validateMediaType(entry.mediaType)
            when (entry.kind) {
                EntryKind.MANIFEST -> archiveCheck(entry.path == MANIFEST_PATH) {
                    "Manifest kind uses an invalid path."
                }
                EntryKind.PAYLOAD -> archiveCheck(entry.path == PAYLOAD_PATH) {
                    "Payload kind uses an invalid path."
                }
                EntryKind.ATTACHMENT -> {
                    archiveCheck(entry.path == "$ATTACHMENT_PREFIX${entry.sha256}") {
                        "Attachment path is not its content SHA-256."
                    }
                    archiveCheck(entry.sha256Scope == ENTRY_HASH_SCOPE) {
                        "Attachment hash scope is invalid."
                    }
                    archiveCheck(entry.length <= limits.maxAttachmentBytes) {
                        "Attachment exceeds the configured byte limit."
                    }
                }
            }
        }
    }

    private fun verifyManifestSelfHash(manifest: Manifest, entry: ManifestEntry) {
        val zeroed = manifest.copy(
            entries = manifest.entries.map {
                if (it.kind == EntryKind.MANIFEST) it.copy(sha256 = ZERO_SHA256) else it
            }
        )
        val expected = sha256(renderManifest(zeroed).toByteArray(StandardCharsets.UTF_8))
        archiveCheck(entry.sha256 == expected) { "Manifest self SHA-256 does not match." }
    }

    private fun writeStoredEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
            time = 0L
            extra = ByteArray(0)
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readEntry(zip: ZipInputStream, limit: Long, path: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            count = safeAdd(count, read.toLong())
            archiveCheck(count <= limit) { "ZIP entry exceeds its byte limit: $path" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validatePath(path: String, limits: Limits) {
        archiveCheck(path.isNotEmpty() && path.length <= limits.maxPathLength) {
            "ZIP entry path length is invalid."
        }
        archiveCheck(path.all { it.code in 0x21..0x7e }) {
            "ZIP entry paths must use printable ASCII without spaces."
        }
        archiveCheck(!path.startsWith('/') && !path.startsWith('\\')) {
            "Absolute ZIP entry paths are forbidden."
        }
        archiveCheck('\\' !in path && ':' !in path) {
            "Backslashes and drive-qualified ZIP paths are forbidden."
        }
        val segments = path.split('/')
        archiveCheck(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "ZIP entry path traversal is forbidden."
        }
    }

    private fun validatePayloadSchema(value: String) {
        archiveCheck(value.matches(Regex("^[a-z][a-z0-9._-]{0,127}@[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
            "payloadSchema must be a canonical, versioned contract identifier."
        }
    }

    private fun validateMediaType(value: String) {
        archiveCheck(value.length <= 255 && value.matches(
            Regex("^[a-z0-9][a-z0-9!#&^_.+-]{0,126}/[a-z0-9][a-z0-9!#&^_.+-]{0,126}$")
        )) { "Media type must be a canonical lowercase type/subtype without parameters." }
    }

    private fun validateSha256(value: String, label: String) {
        archiveCheck(value.matches(Regex("^[0-9a-f]{64}$"))) { "$label is not canonical lowercase hex." }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        archiveCheck(right >= 0L && left <= Long.MAX_VALUE - right) { "Archive byte count overflow." }
        return left + right
    }

    private data class PreparedAttachment(
        val sha256: String,
        val mediaType: String,
        val bytes: ByteArray,
    )

    private enum class EntryKind { MANIFEST, PAYLOAD, ATTACHMENT }

    private data class ManifestEntry(
        val kind: EntryKind,
        val path: String,
        val mediaType: String,
        val length: Long,
        val sha256: String,
        val sha256Scope: String,
    )

    private data class Manifest(
        val payloadSchema: String,
        val entries: List<ManifestEntry>,
    )

    private fun renderManifest(manifest: Manifest): String = buildString {
        append('{')
        append("\"archive_format\":")
        appendJsonString(ARCHIVE_FORMAT)
        append(",\"entries\":[")
        manifest.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('{')
            append("\"kind\":")
            appendJsonString(entry.kind.name)
            append(",\"length\":")
            append(entry.length)
            append(",\"media_type\":")
            appendJsonString(entry.mediaType)
            append(",\"path\":")
            appendJsonString(entry.path)
            append(",\"sha256\":")
            appendJsonString(entry.sha256)
            append(",\"sha256_scope\":")
            appendJsonString(entry.sha256Scope)
            append('}')
        }
        append(']')
        append(",\"format_version\":")
        append(FORMAT_VERSION)
        append(",\"payload_schema\":")
        appendJsonString(manifest.payloadSchema)
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw MaintenanceArchiveException("Manifest is not valid UTF-8.", error)
        }
        val root = JsonParser(text).parse() as? JsonObject
            ?: throw MaintenanceArchiveException("Manifest root must be an object.")
        root.requireExactKeys(setOf("archive_format", "entries", "format_version", "payload_schema"))
        archiveCheck(root.string("archive_format") == ARCHIVE_FORMAT) {
            "Unsupported maintenance archive format."
        }
        archiveCheck(root.long("format_version") == FORMAT_VERSION.toLong()) {
            "Unsupported maintenance archive version."
        }
        val entries = root.array("entries").values.map { rawEntry ->
            val objectEntry = rawEntry as? JsonObject
                ?: throw MaintenanceArchiveException("Manifest entry must be an object.")
            objectEntry.requireExactKeys(
                setOf("kind", "length", "media_type", "path", "sha256", "sha256_scope")
            )
            val kind = try {
                EntryKind.valueOf(objectEntry.string("kind"))
            } catch (error: IllegalArgumentException) {
                throw MaintenanceArchiveException("Manifest entry kind is unsupported.", error)
            }
            ManifestEntry(
                kind = kind,
                path = objectEntry.string("path"),
                mediaType = objectEntry.string("media_type"),
                length = objectEntry.long("length"),
                sha256 = objectEntry.string("sha256"),
                sha256Scope = objectEntry.string("sha256_scope"),
            )
        }
        return Manifest(root.string("payload_schema"), entries)
    }

    private sealed interface JsonValue
    private data class JsonObject(val values: LinkedHashMap<String, JsonValue>) : JsonValue {
        fun requireExactKeys(expected: Set<String>) {
            archiveCheck(values.keys == expected) { "Manifest object keys are missing or unsupported." }
        }

        fun string(key: String): String = (values[key] as? JsonString)?.value
            ?: throw MaintenanceArchiveException("Manifest field '$key' must be a string.")

        fun long(key: String): Long = (values[key] as? JsonInteger)?.value
            ?: throw MaintenanceArchiveException("Manifest field '$key' must be an integer.")

        fun array(key: String): JsonArray = values[key] as? JsonArray
            ?: throw MaintenanceArchiveException("Manifest field '$key' must be an array.")
    }

    private data class JsonArray(val values: List<JsonValue>) : JsonValue
    private data class JsonString(val value: String) : JsonValue
    private data class JsonInteger(val value: Long) : JsonValue
    private data class JsonBoolean(val value: Boolean) : JsonValue
    private data object JsonNull : JsonValue

    /** Strict JSON reader used only for the bounded manifest, including duplicate-key rejection. */
    private class JsonParser(private val text: String) {
        private var cursor = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = value(depth = 0)
            skipWhitespace()
            archiveCheck(cursor == text.length) { "Manifest has trailing JSON content." }
            return value
        }

        private fun value(depth: Int): JsonValue {
            archiveCheck(depth <= 8) { "Manifest JSON nesting is excessive." }
            skipWhitespace()
            archiveCheck(cursor < text.length) { "Manifest JSON ended unexpectedly." }
            return when (text[cursor]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> JsonString(stringValue())
                't' -> literal("true", JsonBoolean(true))
                'f' -> literal("false", JsonBoolean(false))
                'n' -> literal("null", JsonNull)
                '-', in '0'..'9' -> integerValue()
                else -> throw MaintenanceArchiveException("Manifest contains invalid JSON.")
            }
        }

        private fun objectValue(depth: Int): JsonObject {
            expect('{')
            skipWhitespace()
            val fields = LinkedHashMap<String, JsonValue>()
            if (consume('}')) return JsonObject(fields)
            while (true) {
                skipWhitespace()
                archiveCheck(cursor < text.length && text[cursor] == '"') {
                    "Manifest object key must be a string."
                }
                val key = stringValue()
                archiveCheck(!fields.containsKey(key)) { "Manifest contains a duplicate object key." }
                skipWhitespace()
                expect(':')
                fields[key] = value(depth)
                skipWhitespace()
                if (consume('}')) return JsonObject(fields)
                expect(',')
            }
        }

        private fun arrayValue(depth: Int): JsonArray {
            expect('[')
            skipWhitespace()
            val items = mutableListOf<JsonValue>()
            if (consume(']')) return JsonArray(items)
            while (true) {
                items += value(depth)
                skipWhitespace()
                if (consume(']')) return JsonArray(items)
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (cursor < text.length) {
                val character = text[cursor++]
                when (character) {
                    '"' -> {
                        validateUnicode(result)
                        return result.toString()
                    }
                    '\\' -> {
                        archiveCheck(cursor < text.length) { "Manifest JSON escape is incomplete." }
                        when (val escaped = text[cursor++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                archiveCheck(cursor + 4 <= text.length) {
                                    "Manifest Unicode escape is incomplete."
                                }
                                val digits = text.substring(cursor, cursor + 4)
                                archiveCheck(digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                    "Manifest Unicode escape is invalid."
                                }
                                result.append(digits.toInt(16).toChar())
                                cursor += 4
                            }
                            else -> throw MaintenanceArchiveException("Manifest JSON escape is invalid.")
                        }
                    }
                    else -> {
                        archiveCheck(character.code >= 0x20) {
                            "Manifest string contains an unescaped control character."
                        }
                        result.append(character)
                    }
                }
                archiveCheck(result.length <= 4096) { "Manifest string is excessively long." }
            }
            throw MaintenanceArchiveException("Manifest JSON string is unterminated.")
        }

        private fun integerValue(): JsonInteger {
            val start = cursor
            if (text[cursor] == '-') cursor++
            archiveCheck(cursor < text.length) { "Manifest integer is incomplete." }
            if (text[cursor] == '0') {
                cursor++
                archiveCheck(cursor == text.length || text[cursor] !in '0'..'9') {
                    "Manifest integer has a leading zero."
                }
            } else {
                archiveCheck(text[cursor] in '1'..'9') { "Manifest integer is invalid." }
                while (cursor < text.length && text[cursor] in '0'..'9') cursor++
            }
            archiveCheck(cursor == text.length || text[cursor] !in charArrayOf('.', 'e', 'E')) {
                "Manifest numbers must be integers."
            }
            val number = text.substring(start, cursor).toLongOrNull()
                ?: throw MaintenanceArchiveException("Manifest integer is outside the supported range.")
            return JsonInteger(number)
        }

        private fun <T : JsonValue> literal(token: String, value: T): T {
            archiveCheck(text.regionMatches(cursor, token, 0, token.length)) {
                "Manifest JSON literal is invalid."
            }
            cursor += token.length
            return value
        }

        private fun validateUnicode(value: StringBuilder) {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character.isHighSurrogate()) {
                    archiveCheck(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "Manifest string contains an unpaired Unicode surrogate."
                    }
                    index += 2
                } else {
                    archiveCheck(!character.isLowSurrogate()) {
                        "Manifest string contains an unpaired Unicode surrogate."
                    }
                    index++
                }
            }
        }

        private fun skipWhitespace() {
            while (cursor < text.length && text[cursor] in charArrayOf(' ', '\n', '\r', '\t')) cursor++
        }

        private fun expect(character: Char) {
            archiveCheck(cursor < text.length && text[cursor] == character) {
                "Manifest JSON expected '$character'."
            }
            cursor++
        }

        private fun consume(character: Char): Boolean {
            if (cursor < text.length && text[cursor] == character) {
                cursor++
                return true
            }
            return false
        }
    }

    private class BoundedInputStream(
        input: InputStream,
        private val limit: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) increment(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) increment(read)
            return read
        }

        private fun increment(amount: Int) {
            count = safeAdd(count, amount.toLong())
            archiveCheck(count <= limit) { "Maintenance archive exceeds the configured byte limit." }
        }
    }

    private class DigestCountingOutputStream(
        output: OutputStream,
        private val limit: Long,
    ) : FilterOutputStream(output) {
        private val digest = MessageDigest.getInstance("SHA-256")
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            digest.update(value.toByte())
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            out.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            count += length.toLong()
        }

        fun digestHex(): String = digest.digest().toLowerHex()

        private fun ensureCapacity(amount: Int) {
            archiveCheck(amount >= 0 && count <= limit - amount.toLong()) {
                "Maintenance archive exceeds the configured byte limit."
            }
        }
    }

    private inline fun archiveCheck(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw MaintenanceArchiveException(lazyMessage())
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

class MaintenanceArchiveException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
