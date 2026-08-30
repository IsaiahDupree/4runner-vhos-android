package dev.vhos.maintenance

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceArchiveTest {
    private val payload =
        "{\"contract\":\"vehicle.maintenance-ledger-export\",\"records\":[]}".toByteArray()

    @Test
    fun equalInputsProduceIdenticalArchiveBytesAndVerifiedRoundTrip() {
        val receipt = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31)
        val photo = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val firstOutput = ByteArrayOutputStream()
        val writeReceipt = MaintenanceArchiveCodec.write(
            output = firstOutput,
            payload = payload,
            payloadSchema = PAYLOAD_SCHEMA,
            attachments = listOf(
                MaintenanceArchiveCodec.Attachment(receipt, "application/pdf"),
                MaintenanceArchiveCodec.Attachment(photo, "image/jpeg"),
            ),
        )
        val second = MaintenanceArchiveCodec.encode(
            payload = payload,
            payloadSchema = PAYLOAD_SCHEMA,
            attachments = listOf(
                MaintenanceArchiveCodec.Attachment(photo, "image/jpeg"),
                MaintenanceArchiveCodec.Attachment(receipt, "application/pdf"),
            ),
        )

        assertArrayEquals(firstOutput.toByteArray(), second)
        assertEquals(firstOutput.size().toLong(), writeReceipt.archiveLength)
        val decoded = MaintenanceArchiveCodec.decode(second, writeReceipt.archiveSha256)
        assertEquals(PAYLOAD_SCHEMA, decoded.payloadSchema)
        assertArrayEquals(payload, decoded.payload())
        assertEquals(2, decoded.attachments().size)
        assertEquals(writeReceipt.attachmentSha256, decoded.attachments().map { it.sha256 })
        assertTrue(decoded.manifestSha256.matches(Regex("^[0-9a-f]{64}$")))
        val decodedBodies = decoded.attachments().associate { it.mediaType to it.bytes() }
        assertArrayEquals(receipt, decodedBodies.getValue("application/pdf"))
        assertArrayEquals(photo, decodedBodies.getValue("image/jpeg"))
    }

    @Test
    fun payloadTamperWithValidZipCrcIsRejectedByManifestDigest() {
        val valid = MaintenanceArchiveCodec.encode(payload, PAYLOAD_SCHEMA)
        val entries = unzip(valid)
        val entriesByPath = entries.toMap()
        val changedPayload = entriesByPath.getValue(MaintenanceArchiveCodec.PAYLOAD_PATH).copyOf().also {
            it[it.lastIndex] = if (it.last() == '}'.code.toByte()) ']'.code.toByte() else 0x00
        }
        val tampered = zip(
            entries.map { (path, bytes) ->
                path to if (path == MaintenanceArchiveCodec.PAYLOAD_PATH) changedPayload else bytes
            }
        )

        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(tampered)
        }
    }

    @Test
    fun manifestTamperIsRejectedByCanonicalSelfHash() {
        val valid = MaintenanceArchiveCodec.encode(payload, PAYLOAD_SCHEMA)
        val entries = unzip(valid)
        val manifest = entries.toMap().getValue(MaintenanceArchiveCodec.MANIFEST_PATH)
        val original = String(manifest, StandardCharsets.UTF_8)
        val changed = original.replace(PAYLOAD_SCHEMA, "vehicle.maintenance-ledger-exporu@1.0.0")
        assertEquals(original.length, changed.length)
        val tampered = zip(
            entries.map { (path, bytes) ->
                path to if (path == MaintenanceArchiveCodec.MANIFEST_PATH) changed.toByteArray() else bytes
            }
        )

        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(tampered)
        }
    }

    @Test
    fun missingAndUnlistedEntriesAreRejected() {
        val valid = MaintenanceArchiveCodec.encode(
            payload,
            PAYLOAD_SCHEMA,
            attachments = listOf(
                MaintenanceArchiveCodec.Attachment(byteArrayOf(7, 8, 9), "image/png")
            ),
        )
        val entries = unzip(valid)
        val missing = zip(entries.dropLast(1))
        val extra = zip(entries + ("evidence/unlisted.bin" to byteArrayOf(1)))

        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(missing)
        }
        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(extra)
        }
    }

    @Test
    fun duplicateAndTraversalPathsAreRejectedBeforePersistence() {
        val duplicate = localEntriesWithoutCentralDirectory(
            listOf(
                MaintenanceArchiveCodec.MANIFEST_PATH to "{}".toByteArray(),
                MaintenanceArchiveCodec.MANIFEST_PATH to "{}".toByteArray(),
            )
        )
        val traversal = localEntriesWithoutCentralDirectory(
            listOf(
                MaintenanceArchiveCodec.MANIFEST_PATH to "{}".toByteArray(),
                "../escape.json" to "{}".toByteArray(),
            )
        )

        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(duplicate)
        }
        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(traversal)
        }
    }

    @Test
    fun nonCanonicalEntryOrderingIsRejected() {
        val valid = MaintenanceArchiveCodec.encode(payload, PAYLOAD_SCHEMA)
        val entries = unzip(valid)
        val reversed = zip(entries.reversed())

        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(reversed)
        }
    }

    @Test
    fun configuredPayloadAndArchiveLimitsAreEnforced() {
        val valid = MaintenanceArchiveCodec.encode(payload, PAYLOAD_SCHEMA)
        val payloadLimit = MaintenanceArchiveCodec.Limits(maxPayloadBytes = payload.size.toLong() - 1)
        val archiveLimit = MaintenanceArchiveCodec.Limits(maxArchiveBytes = valid.size.toLong() - 1)

        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(valid, limits = payloadLimit)
        }
        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.decode(valid, limits = archiveLimit)
        }
    }

    @Test
    fun duplicateAttachmentContentIsRejectedRatherThanSilentlyReinterpreted() {
        val blob = byteArrayOf(1, 3, 3, 7)
        assertThrows(MaintenanceArchiveException::class.java) {
            MaintenanceArchiveCodec.encode(
                payload,
                PAYLOAD_SCHEMA,
                attachments = listOf(
                    MaintenanceArchiveCodec.Attachment(blob, "image/jpeg"),
                    MaintenanceArchiveCodec.Attachment(blob, "image/jpeg"),
                ),
            )
        }
    }

    private fun unzip(archive: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        val zip = ZipInputStream(ByteArrayInputStream(archive), StandardCharsets.UTF_8)
        while (true) {
            val entry = zip.nextEntry ?: break
            result += entry.name to zip.readBytes()
            zip.closeEntry()
        }
        return result
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        val zip = ZipOutputStream(output, StandardCharsets.UTF_8)
        entries.forEach { (path, bytes) ->
            val crc = CRC32().apply { update(bytes) }
            zip.putNextEntry(ZipEntry(path).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
                time = 0L
            })
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        return output.toByteArray()
    }

    /** Minimal local-file ZIP records let the test create duplicate names ZipOutputStream forbids. */
    private fun localEntriesWithoutCentralDirectory(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        entries.forEach { (path, bytes) ->
            val name = path.toByteArray(StandardCharsets.UTF_8)
            val crc = CRC32().apply { update(bytes) }.value
            output.writeLittleEndian(0x04034b50, 4)
            output.writeLittleEndian(20, 2)
            output.writeLittleEndian(0, 2)
            output.writeLittleEndian(ZipEntry.STORED, 2)
            output.writeLittleEndian(0, 2)
            output.writeLittleEndian(0, 2)
            output.writeLittleEndian(crc, 4)
            output.writeLittleEndian(bytes.size.toLong(), 4)
            output.writeLittleEndian(bytes.size.toLong(), 4)
            output.writeLittleEndian(name.size.toLong(), 2)
            output.writeLittleEndian(0, 2)
            output.write(name)
            output.write(bytes)
        }
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleEndian(value: Number, byteCount: Int) {
        val longValue = value.toLong()
        repeat(byteCount) { index -> write(((longValue ushr (index * 8)) and 0xff).toInt()) }
    }

    private companion object {
        const val PAYLOAD_SCHEMA = "vehicle.maintenance-ledger-export@1.0.0"
    }
}
