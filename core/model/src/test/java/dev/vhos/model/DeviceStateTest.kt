package dev.vhos.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceStateTest {
    @Test
    fun acRecoveryStateNeverClaimsTelemetry() {
        val snapshot = DeviceSnapshot.initial(DeviceRole.AC_SENSOR)
        assertEquals(ConnectionPhase.FIRMWARE_NOT_READY, snapshot.phase)
        assertEquals(IndicatorLevel.WAIT, snapshot.level)
        assertEquals(0, snapshot.logicalFrames)
    }

    @Test
    fun persistedLiveCanRuntimeValueRetainsExactRawEvidenceShape() {
        // Exact 0x2C4 record from the SHA-pinned August 18 real-capture fixture.
        val sample = PersistedLiveCanObservation(
            sourceId = "esp32-9454c5b08d14",
            receivedAtEpochMs = 1_755_538_664_000L,
            sessionId = 627_753_796u,
            sourceSequence = 3_793UL,
            gatewayMonotonicMicroseconds = 8_959_637UL,
            bitrateBps = 500_000,
            identifier = 0x2C4u,
            extended = false,
            dataLength = 8,
            data = listOf(5, 226, 0, 31, 64, 128, 18, 166),
        )

        val snapshot = DeviceSnapshot.initial(DeviceRole.OBD_CAN).copy(
            liveCanObservations = listOf(sample),
        )
        assertEquals(sample, snapshot.liveCanObservations.single())
        assertEquals(listOf(5, 226, 0, 31, 64, 128, 18, 166), sample.data)
    }

    @Test
    fun malformedLiveCanRuntimeValueFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedLiveCanObservation(
                sourceId = "esp32-9454c5b08d14",
                receivedAtEpochMs = 1_755_538_664_000L,
                sessionId = 627_753_796u,
                sourceSequence = 3_793UL,
                gatewayMonotonicMicroseconds = 8_959_637UL,
                bitrateBps = 500_000,
                identifier = 0x2C4u,
                extended = false,
                dataLength = 8,
                data = listOf(5, 226),
            )
        }
    }
}
