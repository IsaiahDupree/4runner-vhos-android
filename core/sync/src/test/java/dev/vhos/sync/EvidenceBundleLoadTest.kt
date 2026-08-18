package dev.vhos.sync

import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

class EvidenceBundleLoadTest {
    @Test
    fun bundleSustains2048ChecksummedRecordsThroughExportAndImport() {
        val recordCount = 2_048
        val records = (0 until recordCount).map { index ->
            val frame = GatewayFrame(
                messageType = MessageType.GATEWAY_HEALTH,
                sequence = (index + 1).toULong(),
                monotonicMicroseconds = (index * 2_000).toULong(),
                payload = deterministicTransportPayload(index, 96),
            )
            val envelope = frame.encode()
            PortableEvidenceRecord(
                sourceRole = "OBD_CAN",
                sourceId = "transport-load-contract",
                sourceSequence = frame.sequence.toString(),
                sourceMonotonicMicroseconds = frame.monotonicMicroseconds.toString(),
                protocolMajor = frame.protocolMajor,
                protocolMinor = frame.protocolMinor,
                messageType = frame.messageType.code,
                flags = frame.flags,
                ingestedAt = "2026-08-18T12:00:00Z",
                envelopeSha256 = EvidenceBundles.sha256(envelope),
                envelopeBase64 = Base64.getEncoder().encodeToString(envelope),
            )
        }

        val archive = EvidenceBundles.toByteArray(
            records = records,
            creator = BundleCreator(
                platform = "ANDROID",
                applicationId = "dev.vhos.headunit.tests",
                applicationVersion = "transport-load-v1",
                deviceModel = "host-contract-runner",
            ),
            bundleId = UUID.fromString("d61d85cf-5960-4ab4-86a5-2279d541f970"),
            createdAt = Instant.parse("2026-08-18T12:00:00Z"),
        )
        val imported = EvidenceBundles.fromByteArray(archive)

        assertEquals(recordCount, imported.records.size)
        assertEquals(records, imported.records)
        assertEquals("1", imported.records.first().sourceSequence)
        assertEquals(recordCount.toString(), imported.records.last().sourceSequence)
    }

    private fun deterministicTransportPayload(index: Int, byteCount: Int): ByteArray =
        ByteArray(byteCount) { byte -> ((index * 31 + byte * 17) % 251).toByte() }
}
