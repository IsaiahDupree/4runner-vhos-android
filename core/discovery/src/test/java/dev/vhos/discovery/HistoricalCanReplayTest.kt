package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalCanReplayTest {
    @Test
    fun realEvidenceSustainsTwentyFullSpeedPassesThroughProductionWirePath() {
        val records = RealCanFixture.load(javaClass)

        val report = HistoricalCanReplay.run(records, repeat = 20)

        assertEquals(HISTORICAL_REPLAY_LABEL, report.label)
        assertEquals(HISTORICAL_REPLAY_SOURCE, report.sourceClassification)
        assertEquals(5_120, report.inputRecords)
        assertEquals(5_120, report.decodedRecords)
        assertEquals(0, report.expectedMissingRecords)
        assertEquals(17, report.uniqueIdentifiers)
        assertEquals(0L, report.decoderRecoveries)
        assertEquals(0L, report.decoderDiscardedBytes)
        assertTrue(report.exactRecordOrderAndPayloadMatch)
        assertTrue(report.passed)
    }

    @Test
    fun realEvidenceReplayCanBeCancelledWithoutClaimingPass() {
        val records = RealCanFixture.load(javaClass)
        var callbacks = 0

        val report = HistoricalCanReplay.run(
            records,
            repeat = 20,
            shouldContinue = { callbacks < 300 },
            onRecord = { callbacks++ },
        )

        assertTrue(report.cancelled)
        assertFalse(report.passed)
        assertEquals(300, report.decodedRecords)
    }

    @Test
    fun realEvidenceFaultProfilesRecoverAndPreserveEveryLaterRecord() {
        val records = RealCanFixture.load(javaClass)

        listOf(
            ReplayFaultProfile.DROP_FRAGMENT,
            ReplayFaultProfile.CORRUPT_PAYLOAD,
            ReplayFaultProfile.DISCONNECT_MID_FRAME,
        ).forEach { fault ->
            val report = HistoricalCanReplay.run(
                records,
                repeat = 3,
                fault = fault,
                faultInterval = 41,
            )

            assertTrue("$fault did not preserve later records", report.exactRecordOrderAndPayloadMatch)
            assertTrue(report.passed)
            assertTrue(report.faultedWireFrames > 0)
            assertTrue(report.expectedMissingRecords > 0)
            assertEquals(report.expectedRecordsAfterFaults, report.decodedRecords)
            assertTrue(report.decoderRecoveries > 0)
        }
    }
}
