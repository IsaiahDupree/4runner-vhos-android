package dev.vhos.headunit

import dev.vhos.model.AcTemperatureChannel
import dev.vhos.model.AcThermalRunPhase
import dev.vhos.model.AcThermalRunSnapshot
import dev.vhos.model.IndicatorLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcThermalRunUiProjectionTest {
    @Test
    fun armedStateShowsNoInventedTemperatureOrDuration() {
        val state = acThermalRunUiState(
            AcThermalRunSnapshot(
                phase = AcThermalRunPhase.ARMED,
                targetChannel = AcTemperatureChannel.CENTER_VENT,
                requestedAtEpochMillis = 1_777_680_000_000L,
                lastLimitation = "Waiting for qualified center vent evidence.",
            )
        )

        assertEquals(IndicatorLevel.ACTIVE, state.level)
        assertTrue(state.badge.contains("WAITING FOR SENSOR"))
        assertEquals("TIME TO MAX  —", state.primaryMetric)
        assertFalse(state.temperatures.any(Char::isDigit))
    }

    @Test
    fun runningStateLabelsPeakTimeProvisionalAndShowsBothTemperatureUnits() {
        val state = acThermalRunUiState(completedEvidence().copy(phase = AcThermalRunPhase.RUNNING))

        assertEquals(IndicatorLevel.ACTIVE, state.level)
        assertTrue(state.badge.contains("PROVISIONAL"))
        assertEquals("TIME TO MAX  12.5 s", state.primaryMetric)
        assertTrue(state.temperatures.contains("20.0 °C / 68.0 °F"))
        assertTrue(state.temperatures.contains("25.0 °C / 77.0 °F"))
    }

    @Test
    fun completedStateDoesNotPresentTheMeasurementAsAcHealth() {
        val state = acThermalRunUiState(completedEvidence())

        assertEquals(IndicatorLevel.CHECK, state.level)
        assertTrue(state.badge.contains("NOT A HEALTH VERDICT"))
        assertTrue(state.detail.contains(AcThermalRunSnapshot.CALCULATION_ID))
    }

    private fun completedEvidence() = AcThermalRunSnapshot(
        phase = AcThermalRunPhase.COMPLETE,
        targetChannel = AcTemperatureChannel.CENTER_VENT,
        requestedAtEpochMillis = 1_777_680_000_000L,
        deviceId = "ac-node-verified",
        captureId = "capture_A",
        acceptedSamples = 6,
        startTemperatureCelsius = 20.0,
        currentTemperatureCelsius = 24.0,
        maximumTemperatureCelsius = 25.0,
        startMonotonicMicroseconds = 2_000_000UL,
        currentMonotonicMicroseconds = 20_000_000UL,
        maximumMonotonicMicroseconds = 14_500_000UL,
        lastSampleCounter = 15UL,
        elapsedMillis = 18_000L,
        timeToMaximumMillis = 12_500L,
    )
}
