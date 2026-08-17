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
}
