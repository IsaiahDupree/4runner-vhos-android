package dev.vhos.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TransportLoadTest {
    /**
     * Contract traffic only. The deterministic payload exercises the production framing, CRC32C,
     * buffering, fragmentation, and sequence paths; it is not reported as vehicle evidence.
     */
    @Test
    fun streamDecoderSustains8192OrderedFramesAcrossHostileFragmentation() {
        val frameCount = 8_192
        val frames = (0 until frameCount).map { index ->
            GatewayFrame(
                messageType = MessageType.GATEWAY_HEALTH,
                sequence = (index + 1).toULong(),
                monotonicMicroseconds = (index * 2_000).toULong(),
                payload = deterministicTransportPayload(index, 160),
            )
        }
        val wire = ByteArrayOutputStream().use { output ->
            frames.forEach { output.write(it.encode()) }
            output.toByteArray()
        }

        val chunkSizes = intArrayOf(1, 3, 20, 244, 5, 509, 64, 17, 1_024)
        val decoder = FrameStreamDecoder()
        val decoded = mutableListOf<GatewayFrame>()
        var offset = 0
        var chunkIndex = 0
        while (offset < wire.size) {
            val count = minOf(chunkSizes[chunkIndex % chunkSizes.size], wire.size - offset)
            decoded += decoder.append(wire.copyOfRange(offset, offset + count))
            offset += count
            chunkIndex += 1
        }

        assertEquals(frameCount, decoded.size)
        assertEquals(frames, decoded)
        assertEquals(frameCount.toULong(), decoded.last().sequence)
        assertEquals(0L, decoder.recoveryCount)
        assertEquals(0L, decoder.discardedByteCount)
    }

    @Test
    fun streamDecoderRecoversAfterOneNotificationFragmentIsLost() {
        val first = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = 41u,
            monotonicMicroseconds = 82_000u,
            payload = deterministicTransportPayload(41, 720),
        ).encode()
        val second = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = 42u,
            monotonicMicroseconds = 84_000u,
            payload = deterministicTransportPayload(42, 720),
        )
        val third = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = 43u,
            monotonicMicroseconds = 86_000u,
            payload = deterministicTransportPayload(43, 720),
        )

        // Reproduce a missing ATT-notification-sized range inside an otherwise valid frame.
        val damagedWire = ByteArrayOutputStream().use { output ->
            output.write(first, 0, 244)
            output.write(first, 488, first.size - 488)
            output.write(second.encode())
            output.write(third.encode())
            output.toByteArray()
        }
        val decoder = FrameStreamDecoder()
        val decoded = mutableListOf<GatewayFrame>()
        var offset = 0
        while (offset < damagedWire.size) {
            val count = minOf(97, damagedWire.size - offset)
            decoded += decoder.append(damagedWire.copyOfRange(offset, offset + count))
            offset += count
        }

        assertEquals(listOf(second, third), decoded)
        assertTrue(decoder.recoveryCount > 0)
        assertTrue(decoder.corruptCandidateCount > 0)
        assertTrue(decoder.discardedByteCount > 0)
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun streamDecoderRecoversFromNoiseAndCorruptPayloadWithoutResettingLink() {
        val first = GatewayFrame(
            messageType = MessageType.RAW_CAN_FRAME,
            sequence = 90u,
            monotonicMicroseconds = 180_000u,
            payload = deterministicTransportPayload(90, 36),
        )
        val second = GatewayFrame(
            messageType = MessageType.RAW_CAN_FRAME,
            sequence = 91u,
            monotonicMicroseconds = 182_000u,
            payload = deterministicTransportPayload(91, 36),
        )
        val corrupt = first.encode().also { bytes ->
            val offset = GatewayFrame.HEADER_LENGTH + 4
            bytes[offset] = (bytes[offset].toInt() xor 0x80).toByte()
        }
        val wire = byteArrayOf(0x00, 0x56, 0x48, 0x99.toByte(), 0x01) + corrupt + second.encode()

        val decoder = FrameStreamDecoder()
        val decoded = decoder.append(wire)

        assertEquals(listOf(second), decoded)
        assertTrue(decoder.recoveryCount >= 2)
        assertEquals(1L, decoder.corruptCandidateCount)
        assertEquals(0, decoder.bufferedByteCount)
    }

    private fun deterministicTransportPayload(index: Int, byteCount: Int): ByteArray =
        ByteArray(byteCount) { byte -> ((index * 31 + byte * 17) % 251).toByte() }
}
