package dev.vhos.sync

import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EvidenceBundleTest {
    private val envelope = GatewayFrame(
        messageType = MessageType.GATEWAY_HEALTH,
        sequence = 42u,
        monotonicMicroseconds = 9_001u,
        payload = "{\"contract\":\"gateway.health\"}".toByteArray(),
    ).encode()
    private val record = PortableEvidenceRecord(
        sourceRole = "OBD_CAN",
        sourceId = "esp32-test",
        sourceSequence = "42",
        sourceMonotonicMicroseconds = "9001",
        protocolMajor = 1,
        protocolMinor = 0,
        messageType = 4,
        flags = 0,
        ingestedAt = "2026-08-17T12:00:00Z",
        envelopeSha256 = EvidenceBundles.sha256(envelope),
        envelopeBase64 = Base64.getEncoder().encodeToString(envelope),
    )

    @Test
    fun checksummedBundleRoundTripsAndRetainsIdentity() {
        val bytes = EvidenceBundles.toByteArray(
            records = listOf(record),
            creator = BundleCreator("ANDROID", "dev.vhos.headunit", "0.1.0", "head-unit"),
            bundleId = UUID.fromString("7efec738-4535-4c66-9ec5-64bda8ed57fb"),
            createdAt = Instant.parse("2026-08-17T12:00:00Z"),
        )
        val imported = EvidenceBundles.fromByteArray(bytes)
        assertEquals("7efec738-4535-4c66-9ec5-64bda8ed57fb", imported.manifest.bundleId)
        assertEquals(1, imported.records.size)
        assertEquals("42", imported.records.single().sourceSequence)
    }

    @Test
    fun rejectsTamperedSegmentBeforeReturningRecords() {
        val valid = EvidenceBundles.toByteArray(
            records = listOf(record),
            creator = BundleCreator("ANDROID", "dev.vhos.headunit", "0.1.0", "head-unit"),
        )
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(valid)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val path = "segments/logical-frames.ndjson"
        entries[path] = entries.getValue(path) + "tampered".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertThrows(BundleException::class.java) {
            EvidenceBundles.read(ByteArrayInputStream(output.toByteArray()))
        }
    }

    @Test
    fun importsGoldenBundleWrittenByIos() {
        val archive = Base64.getDecoder().decode(IOS_GOLDEN_BUNDLE_BASE64)
        val imported = EvidenceBundles.fromByteArray(archive)
        assertEquals("IOS", imported.manifest.creator.platform)
        assertEquals("7EFEC738-4535-4C66-9EC5-64BDA8ED57FB".lowercase(), imported.manifest.bundleId.lowercase())
        assertEquals("esp32-test", imported.records.single().sourceId)
        assertEquals("42", imported.records.single().sourceSequence)
    }

    companion object {
        private const val IOS_GOLDEN_BUNDLE_BASE64 =
            "UEsDBBQAAAAAAAAAAADOeK5f6gEAAOoBAAANAAAAbWFuaWZlc3QuanNvbnsiYnVuZGxlX2lkIjoiN0VGRUM3MzgtNDUzNS00QzY2LTlFQzUtNjRCREE4RUQ1N0ZCIiwiY29udHJhY3QiOiJ2aG9zLmV2aWRlbmNlLXN5bmMtYnVuZGxlIiwiY29udHJhY3RfdmVyc2lvbiI6IjEuMC4wIiwiY3JlYXRlZF9hdCI6IjIwMjYtMDgtMTdUMTI6MDA6MDBaIiwiY3JlYXRvciI6eyJhcHBsaWNhdGlvbl9pZCI6ImNvbS5pc2FpYWhkdXByZWUuVmVoaWNsZUhlYWx0aE9TIiwiYXBwbGljYXRpb25fdmVyc2lvbiI6IjAuMy4xIiwiZGV2aWNlX21vZGVsIjoiaVBob25lIiwicGxhdGZvcm0iOiJJT1MifSwic2VnbWVudHMiOlt7ImJ5dGVfY291bnQiOjQ3NywibWVkaWFfdHlwZSI6ImFwcGxpY2F0aW9uL3gtbmRqc29uIiwicGF0aCI6InNlZ21lbnRzL2xvZ2ljYWwtZnJhbWVzLm5kanNvbiIsInJlY29yZF9jb3VudCI6MSwic2hhMjU2IjoiYTY2OTVjMWU5YTZlNTU0Mjk0ZDU4YmQ1Y2MzODM2MTMyMzdmODdkYmEzZDJhMjU5ZGE5NmM1NWQyMjNhMmQ3MSJ9XX1QSwMEFAAAAAAAAAAAABvWj9LdAQAA3QEAAB4AAABzZWdtZW50cy9sb2dpY2FsLWZyYW1lcy5uZGpzb257ImNvbnRyYWN0Ijoidmhvcy5wb3J0YWJsZS1sb2dpY2FsLWZyYW1lIiwiY29udHJhY3RfdmVyc2lvbiI6IjEuMC4wIiwiZW52ZWxvcGVfYmFzZTY0IjoiVmtoUFV3RUFCQUFkQUFBQUtnQUFBQUFBQUFBcEl3QUFBQUFBQUJaOFl3ZlVieEhmZXlKamIyNTBjbUZqZENJNkltZGhkR1YzWVhrdWFHVmhiSFJvSW4wPSIsImVudmVsb3BlX3NoYTI1NiI6ImFmOGEyMWIwYTgwOTFiNjM3YTU2YjZlYmUxMGYwNGFlZmFkMWZmN2E0NDRlNzEyZjc5ZmI0ZjlhZThkMzQ4ZTIiLCJmbGFncyI6MCwiaW5nZXN0ZWRfYXQiOiIyMDI2LTA4LTE3VDEyOjAwOjAwWiIsIm1lc3NhZ2VfdHlwZSI6NCwicHJvdG9jb2xfbWFqb3IiOjEsInByb3RvY29sX21pbm9yIjowLCJzb3VyY2VfaWQiOiJlc3AzMi10ZXN0Iiwic291cmNlX21vbm90b25pY19taWNyb3NlY29uZHMiOiI5MDAxIiwic291cmNlX3JvbGUiOiJPQkRfQ0FOIiwic291cmNlX3NlcXVlbmNlIjoiNDIifQpQSwECFAAUAAAAAAAAAAAAzniuX+oBAADqAQAADQAAAAAAAAAAAAAAAAAAAAAAbWFuaWZlc3QuanNvblBLAQIUABQAAAAAAAAAAAAb1o/S3QEAAN0BAAAeAAAAAAAAAAAAAAAAABUCAABzZWdtZW50cy9sb2dpY2FsLWZyYW1lcy5uZGpzb25QSwUGAAAAAAIAAgCHAAAALgQAAAAA"
    }
}
