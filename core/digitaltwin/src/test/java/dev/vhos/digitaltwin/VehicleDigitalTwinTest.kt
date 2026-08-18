package dev.vhos.digitaltwin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDigitalTwinTest {
    @Test
    fun v6AlwaysResolvesToTimingChainAndV8ToTimingBelt() {
        assertEquals(TimingDrive.TIMING_CHAIN, EngineConfiguration.V6_4_0L_1GR_FE.timingDrive)
        assertEquals(TimingDrive.TIMING_BELT, EngineConfiguration.V8_4_7L_2UZ_FE.timingDrive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun profileRejectsV6TimingBeltMismatch() {
        VehicleProfile(
            engine = EngineConfiguration.V6_4_0L_1GR_FE,
            timingDrive = TimingDrive.TIMING_BELT,
        ).validate()
    }

    @Test
    fun unknownHealthMapCoversEverySystemWithoutMakingHealthClaims() {
        val assessments = VehicleSystem.entries.map { HealthAssessment.unknown(it, null) }

        assertEquals(VehicleSystem.entries.size, assessments.size)
        assertTrue(assessments.all { it.state == HealthState.UNKNOWN })
        assertTrue(assessments.all { it.basis == EvidenceBasis.UNKNOWN })
        assertTrue(assessments.all { it.evidenceRefs.isEmpty() })
        assertEquals(VehicleSystem.entries.size, HealthSummary.from(assessments).unknownSystems)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonUnknownHealthRequiresEvidence() {
        HealthAssessment(
            systemId = VehicleSystem.BRAKES,
            state = HealthState.OK,
            basis = EvidenceBasis.INSPECTION,
            summary = "Brake pads inspected.",
        ).validate()
    }

    @Test
    fun scheduleReadinessNeverTreatsUnknownConfigurationAsComplete() {
        val incomplete = VehicleProfile()
        assertFalse(incomplete.scheduleReady)
        assertTrue(incomplete.scheduleReadinessIssues().contains("engine"))

        val complete = VehicleProfile(
            vin = "JTEBU14R750012345",
            engine = EngineConfiguration.V6_4_0L_1GR_FE,
            drivetrain = Drivetrain.FOUR_WHEEL_DRIVE,
            rearSuspension = RearSuspension.CONVENTIONAL,
            trim = "SR5",
            buildDate = "2005-03",
            tireConfiguration = "265/65R17",
            severeUse = TriState.NO,
            modificationState = ModificationState.STOCK,
            currentMileage = 154_000,
            mileageObservedAt = "2026-08-18T12:00:00Z",
            mileageSource = MileageSource.MANUAL_ODOMETER,
        ).validate()
        assertTrue(complete.scheduleReady)
    }
}
