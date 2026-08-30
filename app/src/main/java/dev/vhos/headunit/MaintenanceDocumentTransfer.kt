package dev.vhos.headunit

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.vhos.maintenance.MaintenanceLedgerArchivePayload
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Bounded, canonical file-transfer helpers shared by the maintenance SAF flows. */
internal object MaintenanceDocumentTransfer {
    const val MAX_ATTACHMENT_BYTES: Int = 64 * 1024 * 1024

    private val mediaTypePattern =
        Regex("^[a-z0-9][a-z0-9!#&^_.+-]{0,126}/[a-z0-9][a-z0-9!#&^_.+-]{0,126}$")
    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .disableHtmlEscaping()
        .create()

    /**
     * Reads at most [maxBytes] and consumes one additional byte to prove whether the source is
     * oversized. The input is owned by the caller and is not closed here.
     */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_ATTACHMENT_BYTES): ByteArray {
        require(maxBytes > 0) { "The attachment byte limit must be positive." }
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE * 8))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(total <= maxBytes - read) {
                "Document exceeds the ${maxBytes / (1024 * 1024)} MB encrypted-storage limit."
            }
            output.write(buffer, 0, read)
            total += read
        }
        require(total > 0) { "The selected document is empty." }
        return output.toByteArray()
    }

    fun canonicalMediaType(providerValue: String?): String {
        val candidate = providerValue
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        return candidate.takeIf(mediaTypePattern::matches) ?: "application/octet-stream"
    }

    fun boundedDisplayName(providerValue: String?, fallback: String): String {
        val normalized = providerValue
            ?.replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: fallback
        return normalized.take(255)
    }

    fun archiveFileName(vehicleName: String, exportedAtEpochSecond: Long): String {
        val slug = vehicleName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "vehicle" }
        return "vhos-$slug-maintenance-$exportedAtEpochSecond.vhosmaintenance"
    }

    fun payloadJson(payload: MaintenanceLedgerArchivePayload): ByteArray =
        gson.toJson(payload.validate()).toByteArray(StandardCharsets.UTF_8)
}
