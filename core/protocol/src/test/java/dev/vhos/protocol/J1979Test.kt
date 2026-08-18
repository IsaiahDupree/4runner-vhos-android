package dev.vhos.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class J1979Test {
    @Test
    fun passiveWirePreservesGatewayTimelineAndEcu() {
        val payload = passiveWire(byteArrayOf(0x41, 0x00, 0x08, 0x18, 0x00, 0x01))
        val response = J1979ResponseEvidence.decodePassiveWire(payload, "esp32-9454c5b08d14", "2026-08-18T12:00:00Z")

        assertEquals("capture-9", response.captureId)
        assertEquals("0x7E8", response.ecuAddress)
        assertEquals(1234uL, response.sourceSequence)
        assertEquals(5678uL, response.gatewayMonotonicMicroseconds)
        assertEquals(0x00, response.requestPid)
    }

    @Test
    fun valuesDecodeOnlyAfterCompleteAvailabilityEvidence() {
        val accumulator = J1979Accumulator()
        assertNull(accumulator.ingest(response(0x0C, byteArrayOf(0x41, 0x0C, 0x15, 0x6C))))

        accumulator.ingest(response(0x00, byteArrayOf(0x41, 0x00, 0x00, 0x18, 0x00, 0x00)))
        assertTrue(accumulator.availability.single().enumerationComplete)
        val sample = accumulator.ingest(response(0x0C, byteArrayOf(0x41, 0x0C, 0x15, 0x6C)))

        assertEquals("obd.engine.speed", sample?.signalId)
        assertEquals(1371.0, sample?.value ?: 0.0, 0.0001)
        assertEquals("rpm", sample?.unit)
    }

    @Test
    fun continuationBitRequiresNextBitmapFromSameEcu() {
        val accumulator = J1979Accumulator()
        accumulator.ingest(response(0x00, byteArrayOf(0x41, 0x00, 0x00, 0x18, 0x00, 0x01)))
        assertFalse(accumulator.availability.single().enumerationComplete)
        assertEquals(listOf(0x0C, 0x0D, 0x20), accumulator.availability.single().supportedPids)

        accumulator.ingest(response(0x20, byteArrayOf(0x41, 0x20, 0x00, 0x00, 0x00, 0x00)))
        assertTrue(accumulator.availability.single().enumerationComplete)
    }

    @Test
    fun newCaptureCannotReusePriorSupportedPidEnumeration() {
        val accumulator = J1979Accumulator()
        accumulator.ingest(
            response(0x00, byteArrayOf(0x41, 0x00, 0x00, 0x18, 0x00, 0x00), "capture-one")
        )
        assertTrue(accumulator.availability.single().enumerationComplete)

        val result = accumulator.ingest(
            response(0x0C, byteArrayOf(0x41, 0x0C, 0x15, 0x6C), "capture-two")
        )

        assertNull(result)
        assertTrue(accumulator.availability.isEmpty())
        assertTrue(accumulator.standardSamples.isEmpty())
    }

    @Test
    fun finalBitmapDoesNotOverflowPastPid255() {
        assertTrue(
            J1979Accumulator.decodeSupportedBitmap(
                0xE0,
                byteArrayOf(0x00, 0x00, 0x00, 0x01),
            ).isEmpty()
        )
    }

    private fun response(
        pid: Int,
        payload: ByteArray,
        captureId: String = "capture-9",
    ) = J1979ResponseEvidence(
        gatewayId = "esp32-9454c5b08d14",
        captureId = captureId,
        observedAt = "2026-08-18T12:00:00Z",
        gatewayMonotonicMicroseconds = 5678uL,
        sourceSequence = 1234uL,
        transport = "ISO_15765_11_500",
        ecuAddress = "0x7E8",
        requestPid = pid,
        responsePayload = payload,
    )

    private fun passiveWire(response: ByteArray): ByteArray = ByteArray(36).also { bytes ->
        bytes[0] = 1
        bytes[1] = 1
        bytes[2] = response.size.toByte()
        bytes.putU32(4, 0x7E8u)
        bytes.putU64(8, 1234uL)
        bytes.putU64(16, 5678uL)
        bytes.putU32(24, 9u)
        response.copyInto(bytes, 28)
    }
}
