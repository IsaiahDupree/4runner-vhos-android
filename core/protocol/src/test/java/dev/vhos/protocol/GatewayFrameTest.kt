package dev.vhos.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayFrameTest {
    @Test
    fun frameRoundTripsAcrossTransportChunks() {
        val first = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = 7u,
            monotonicMicroseconds = 42u,
            payload = "health".toByteArray(),
        )
        val second = GatewayFrame(
            messageType = MessageType.EXPERIMENT_RESULT,
            sequence = 8u,
            monotonicMicroseconds = 99u,
            payload = "result".toByteArray(),
        )
        assertEquals(first, GatewayFrame.decode(first.encode()))
        val combined = first.encode() + second.encode()
        val decoder = FrameStreamDecoder()
        assertEquals(emptyList<GatewayFrame>(), decoder.append(combined.copyOfRange(0, 11)))
        assertEquals(listOf(first, second), decoder.append(combined.copyOfRange(11, combined.size)))
    }

    @Test
    fun frameRejectsCorruptionAndTrailingBytes() {
        val encoded = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = 1u,
            monotonicMicroseconds = 1u,
            payload = byteArrayOf(1, 2, 3),
        ).encode()
        encoded[GatewayFrame.HEADER_LENGTH] = (encoded[GatewayFrame.HEADER_LENGTH].toInt() xor 0xFF).toByte()
        assertThrows(FrameException.PayloadCrc::class.java) { GatewayFrame.decode(encoded) }

        val valid = GatewayFrame(
            messageType = MessageType.HANDSHAKE,
            sequence = 2u,
            monotonicMicroseconds = 3u,
            payload = byteArrayOf(),
        ).encode()
        assertThrows(FrameException.TrailingBytes::class.java) { GatewayFrame.decode(valid + 0) }
    }

    @Test
    fun crc32cUsesCastagnoliKnownVector() {
        assertEquals(0xE3069283u, Crc32c.checksum("123456789".toByteArray()))
    }

    @Test
    fun captureLogRequestMatchesFirmwareLayout() {
        val request = CaptureLogRequest(
            operation = CaptureLogRequest.Operation.READ,
            slot = 1,
            recordOffset = 0x78563412u,
        )
        assertArrayEquals(byteArrayOf(1, 1, 1, 0, 0x12, 0x34, 0x56, 0x78), request.encode())
    }

    @Test
    fun captureLogChunkValidatesStoredRecordsAndMaterializesCanEvidence() {
        val record = ByteArray(CanObservation.RECORD_BYTES).also { bytes ->
            bytes[0] = 1
            bytes[1] = 0x04
            bytes[2] = 8
            bytes[3] = 1
            bytes.putU32(4, 0x2C4u)
            bytes.putU64(8, 123uL)
            bytes.putU64(16, 456uL)
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).copyInto(bytes, 24)
            bytes.putU32(32, Crc32c.checksum(bytes, 0, 32))
        }
        val payload = ByteArray(16 + record.size).also { bytes ->
            bytes[0] = 1
            bytes[1] = 1
            bytes[2] = 1
            bytes.putU32(4, 9u)
            bytes[8] = 1
            bytes[10] = CanObservation.RECORD_BYTES.toByte()
            bytes.putU32(12, 77u)
            record.copyInto(bytes, 16)
        }
        val frame = GatewayFrame(
            messageType = MessageType.CAPTURE_LOG_CHUNK,
            sequence = 10u,
            monotonicMicroseconds = 11u,
            payload = payload,
        )

        val chunk = CaptureLogChunk.decode(payload)
        assertEquals(1, chunk.slot)
        assertEquals(true, chunk.endOfFile)
        assertEquals(9u, chunk.recordOffset)
        assertEquals(77u, chunk.sessionId)
        assertEquals(1, chunk.records.size)
        assertEquals(0x2C4u, chunk.records.single().identifier)
        assertEquals(123uL, chunk.records.single().sourceSequence)
        assertEquals(500_000, chunk.records.single().bitrateBps)
        assertEquals(true, chunk.records.single().listenOnly)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), chunk.records.single().data)
        assertEquals(chunk.records, frame.decodeCanObservations())
    }

    @Test
    fun captureLogChunkRejectsAStoredRecordWithInvalidCrc() {
        val record = ByteArray(CanObservation.RECORD_BYTES).also { bytes ->
            bytes[0] = 1
            bytes[1] = 0x04
            bytes[2] = 1
            bytes[3] = 1
            bytes.putU64(8, 1uL)
            bytes.putU64(16, 2uL)
            bytes[24] = 3
            bytes.putU32(32, Crc32c.checksum(bytes, 0, 32))
            bytes[24] = 4
        }
        val payload = ByteArray(16 + record.size).also { bytes ->
            bytes[0] = 1
            bytes[8] = 1
            bytes[10] = CanObservation.RECORD_BYTES.toByte()
            bytes.putU32(12, 5u)
            record.copyInto(bytes, 16)
        }

        assertThrows(PayloadException::class.java) { CaptureLogChunk.decode(payload) }
    }
}
