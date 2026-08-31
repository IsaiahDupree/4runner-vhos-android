package dev.vhos.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcThermalRunTest {
    @Test
    fun firstQualifiedSampleDefinesStartAndLaterPeakDefinesElapsedTime() {
        var run = AcThermalRunReducer.start(
            AcThermalRunSnapshot(),
            requestedAtEpochMillis = 1_777_680_000_000L,
        )
        run = AcThermalRunReducer.accept(run, observation(10UL, 2_000_000UL, 21.0))
        run = AcThermalRunReducer.accept(run, observation(11UL, 4_500_000UL, 23.5))
        run = AcThermalRunReducer.accept(run, observation(12UL, 7_000_000UL, 22.0))

        assertEquals(AcThermalRunPhase.RUNNING, run.phase)
        assertEquals(21.0, run.startTemperatureCelsius!!, 0.0)
        assertEquals(22.0, run.currentTemperatureCelsius!!, 0.0)
        assertEquals(23.5, run.maximumTemperatureCelsius!!, 0.0)
        assertEquals(2_500L, run.timeToMaximumMillis)
        assertEquals(5_000L, run.elapsedMillis)
        assertEquals(2.5, run.temperatureRiseCelsius!!, 0.0)
        assertFalse(run.resultFinal)

        run = AcThermalRunReducer.end(run)
        assertEquals(AcThermalRunPhase.COMPLETE, run.phase)
        assertTrue(run.resultFinal)
        assertEquals(2_500L, run.timeToMaximumMillis)
    }

    @Test
    fun unqualifiedSampleCannotStartAResult() {
        val armed = AcThermalRunReducer.start(
            AcThermalRunSnapshot(),
            requestedAtEpochMillis = 1_777_680_000_000L,
        )
        val rejected = AcThermalRunReducer.accept(
            armed,
            observation(
                counter = 10UL,
                monotonicMicroseconds = 2_000_000UL,
                valueCelsius = 21.0,
                quality = AcTemperatureQuality.SENSOR_UNVERIFIED,
                calibration = AcTemperatureCalibration(configured = false),
            ),
        )

        assertEquals(AcThermalRunPhase.ARMED, rejected.phase)
        assertEquals(1L, rejected.rejectedSamples)
        assertEquals(0L, rejected.acceptedSamples)
        assertNull(rejected.timeToMaximumMillis)
        assertTrue(rejected.lastLimitation!!.contains("validated calibration"))
    }

    @Test
    fun sourceChangeInvalidatesInsteadOfCombiningCaptures() {
        var run = AcThermalRunReducer.start(
            AcThermalRunSnapshot(),
            requestedAtEpochMillis = 1_777_680_000_000L,
        )
        run = AcThermalRunReducer.accept(run, observation(10UL, 2_000_000UL, 21.0))
        run = AcThermalRunReducer.accept(
            run,
            observation(11UL, 4_500_000UL, 23.5).copy(captureId = "capture_B"),
        )

        assertEquals(AcThermalRunPhase.INVALID, run.phase)
        assertNull(run.timeToMaximumMillis)
        assertTrue(run.invalidReason!!.contains("capture changed"))
    }

    @Test
    fun coolingRunReportsFirstSampleAsMaximumWithoutCallingItStabilization() {
        var run = AcThermalRunReducer.start(
            AcThermalRunSnapshot(),
            requestedAtEpochMillis = 1_777_680_000_000L,
        )
        run = AcThermalRunReducer.accept(run, observation(10UL, 2_000_000UL, 31.0))
        run = AcThermalRunReducer.accept(run, observation(11UL, 7_000_000UL, 20.0))
        run = AcThermalRunReducer.end(run)

        assertEquals(31.0, run.maximumTemperatureCelsius!!, 0.0)
        assertEquals(0L, run.timeToMaximumMillis)
        assertEquals(AcThermalRunSnapshot.CALCULATION_ID, "AC.TEMP.TIME_TO_MAX.v1")
    }

    private fun observation(
        counter: ULong,
        monotonicMicroseconds: ULong,
        valueCelsius: Double,
        quality: AcTemperatureQuality = AcTemperatureQuality.GOOD,
        calibration: AcTemperatureCalibration = AcTemperatureCalibration(
            configured = true,
            calibrationId = "ac.temperature.center-vent.primary",
            revision = "1.0.0",
            validationStatus = AcCalibrationValidationStatus.BENCH_VALIDATED,
        ),
    ) = AcTemperatureObservation(
        deviceId = "ac-node-verified",
        captureId = "capture_A",
        sampleCounter = counter,
        observedAtMonotonicMicroseconds = monotonicMicroseconds,
        receivedAtEpochMillis = 1_777_680_000_000L + monotonicMicroseconds.toLong() / 1_000L,
        channel = AcTemperatureChannel.CENTER_VENT,
        valueCelsius = valueCelsius,
        quality = quality,
        calibration = calibration,
    )
}
