package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        assertEquals(256, report.provenance.localAuthorizedRecords)
    }

    @Test
    fun replayPreservesImportedAndRecoveredAuthorityClasses() {
        val source = RealCanFixture.load(javaClass).take(2)
        val records = listOf(
            source[0].copy(provenance = DiscoveryEvidenceProvenance.IMPORTED_V1_HISTORY),
            source[1].copy(provenance = DiscoveryEvidenceProvenance.RECOVERED_V2_HISTORY),
        )
        val observed = mutableListOf<DiscoveryEvidenceProvenance>()

        val report = HistoricalCanReplay.run(records, onRecord = { observed += it.provenance })

        assertEquals(
            listOf(
                DiscoveryEvidenceProvenance.IMPORTED_V1_HISTORY,
                DiscoveryEvidenceProvenance.RECOVERED_V2_HISTORY,
            ),
            observed,
        )
        assertEquals(1, report.provenance.importedV1Records)
        assertEquals(1, report.provenance.recoveredV2Records)
        assertEquals(0, report.provenance.localAuthorizedRecords)
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

    @Test
    fun replayRejectsUnsignedClockWrapAndOffsetOverflow() {
        val source = RealCanFixture.load(javaClass).first()
        val sequenceWrap = listOf(
            source.copy(observation = source.observation.copy(
                sourceSequence = ULong.MAX_VALUE,
                monotonicMicroseconds = 10UL,
            )),
            source.copy(observation = source.observation.copy(
                sourceSequence = 0UL,
                monotonicMicroseconds = 20UL,
            )),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalCanReplay.run(sequenceWrap)
        }

        val offsetOverflow = listOf(
            source.copy(observation = source.observation.copy(
                sourceSequence = 1UL,
                monotonicMicroseconds = 0UL,
            )),
            source.copy(observation = source.observation.copy(
                sourceSequence = 2UL,
                monotonicMicroseconds = ULong.MAX_VALUE,
            )),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalCanReplay.run(offsetOverflow)
        }
    }
}
