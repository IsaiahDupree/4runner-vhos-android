package dev.vhos.protocol

import dev.vhos.model.ACBatteryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ACBatteryPayloadsTest {
    // From 4runner-ac-telemetry-node/tests/ac_battery_wire_vector.c using battery_wire.c.
    private val fixture = "56484f5301000e0020000000200000000000000020402c0000000000606211589c7c22c101030000000000c330f8c0f980497837004b2062ed09c00364008ffd00000000"
    private val raw = fixture.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun decodesTheEsp32FrameAndSuppressesTimeToFullDuringDischarge() {
        val frame = ACBatteryPayloads.frame(raw, MessageType.AC_BATTERY)
        val pack = ACBatteryPayloads.reading(frame)
        assertEquals(32uL, frame.sequence)
        assertEquals(2_900_000uL, frame.monotonicMicroseconds)
        assertEquals(3.9, pack.volts)
        assertEquals(-312.5, pack.milliamps)
        assertEquals(-250.0, pack.averageMilliamps)
        assertEquals(73.5, pack.stateOfCharge)
        assertEquals(98.125, pack.healthPercent)
        assertEquals(7100.0, pack.remainingMilliampHours)
        assertEquals(9600.0, pack.fullMilliampHours)
        assertEquals(25.41, pack.equivalentCycles)
        assertEquals(5400.0, pack.secondsToEmpty)
        assertNull(pack.secondsToFull)
        assertEquals(-6.25, pack.packCelsius)
    }

    @Test
    fun rejectsCrcCorruptionAndLeavesUnavailableMeasurementsEmpty() {
        val corrupted = raw.clone()
        corrupted[42] = (corrupted[42].toInt() xor 1).toByte()
        assertThrows(FrameException.PayloadCrc::class.java) {
            ACBatteryPayloads.frame(corrupted, MessageType.AC_BATTERY)
        }
        val source = ACBatteryPayloads.frame(raw, MessageType.AC_BATTERY)
        val payload = source.payload.clone()
        payload[1] = 0
        payload[2] = 1
        val pack = ACBatteryPayloads.reading(source.copy(payload = payload))
        assertEquals(ACBatteryResult.GAUGE_IO, pack.result)
        assertNull(pack.volts)
        assertNull(pack.stateOfCharge)
        assertNull(pack.packCelsius)
    }
}
