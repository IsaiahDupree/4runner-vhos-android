package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanDiscoveryLoadTest {
    @Test
    fun analyzerProcessesPinnedRealEvidenceWithoutInventingSignalMeaning() {
        val records = RealCanFixture.load(javaClass)

        val report = CanDiscoveryAnalyzer.analyze(records)

        assertEquals(256, report.acquisition.records)
        assertEquals(17, report.acquisition.uniqueIdentifiers)
        assertEquals(256, report.acquisition.listenOnlyRecords)
        assertEquals(558.599496, report.acquisition.estimatedObservedRateFps, 0.000001)
        assertEquals(36.932198, report.acquisition.retainedRecordRateFps, 0.000001)
        assertEquals(0.066099, report.acquisition.sequenceCoverage, 0.000001)
        assertEquals(8, report.identifierActivity.count { it.checksum.candidate })
        assertTrue(report.rawWordRelationships.any {
            it.leftIdentifier == 0x2C4u && it.rightIdentifier == 0x2D0u
        })
        assertTrue(report.repeatedChannels.any {
            it.identifier == 0x025u && it.bytePositions == listOf(4, 5, 6)
        })
        assertEquals("DISCOVERY_CANDIDATE", report.status)
        assertTrue(report.authority.contains("no identifier"))
    }
}
