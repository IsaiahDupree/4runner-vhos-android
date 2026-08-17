package dev.vhos.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStateTest {
    @Test
    fun acRecoveryStateNeverClaimsTelemetry() {
        val snapshot = DeviceSnapshot.initial(DeviceRole.AC_SENSOR)
        assertEquals(ConnectionPhase.FIRMWARE_NOT_READY, snapshot.phase)
        assertEquals(IndicatorLevel.WAIT, snapshot.level)
        assertEquals(0, snapshot.logicalFrames)
    }
}
