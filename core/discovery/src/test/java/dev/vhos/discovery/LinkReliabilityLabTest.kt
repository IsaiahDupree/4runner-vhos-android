package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkReliabilityLabTest {
    @Test
    fun pinnedRealEvidencePassesFullDegradedLinkMatrixAndFortyCycleSoak() {
        val report = LinkReliabilityLab.run(
            input = RealCanFixture.load(javaClass),
            soakCycles = 40,
        )

        assertEquals(LINK_RELIABILITY_LABEL, report.label)
        assertEquals(HISTORICAL_REPLAY_SOURCE, report.sourceClassification)
        assertEquals(LINK_RELIABILITY_CONTRACT_VERSION, report.contractVersion)
        assertEquals(15, report.scenarios.size)
        assertEquals(5, report.healthyScenarios)
        assertEquals(10, report.degradedScenarios)
        assertEquals(13_824, report.totalWireDeliveries)
        assertTrue(report.passed)
        assertTrue(report.scenarios.all { it.passed })
        assertTrue(report.scenarios.all { it.exactExpectedSurvivorOrderAndPayload })
        assertTrue(
            report.scenarios.all {
                it.decoderMaximumBufferedBytes <= LINK_RELIABILITY_MAXIMUM_BUFFER_BYTES
            }
        )

        val scenarios = report.scenarios.associateBy { it.scenario }
        val clean = checkNotNull(scenarios[LinkReliabilityScenario.CLEAN_SOAK])
        assertEquals(10_240, clean.wireDeliveries)
        assertEquals(256, clean.acceptedUniqueRecords)
        assertEquals(9_984L, clean.duplicateIdentityRejections)
        assertEquals(0L, clean.decoderRecoveries)

        val duplicate = checkNotNull(scenarios[LinkReliabilityScenario.DUPLICATE_FRAME])
        assertEquals(256, duplicate.acceptedUniqueRecords)
        assertTrue(duplicate.duplicateIdentityRejections > 0)

        val reconnect = checkNotNull(scenarios[LinkReliabilityScenario.MID_FRAME_RECONNECT])
        assertTrue(reconnect.reconnects > 0)
        assertTrue(reconnect.staleEpochNotificationRejections > 0)

        val overrun = checkNotNull(scenarios[LinkReliabilityScenario.BOUNDED_QUEUE_OVERRUN])
        assertTrue(overrun.outerSequenceGaps > 0UL)
        assertEquals(0L, overrun.decoderRecoveries)
        assertEquals(LinkQuality.DEGRADED, overrun.observedQuality)
    }

    @Test(expected = IllegalStateException::class)
    fun cancellationStopsTheMatrixBetweenDeterministicWorkUnits() {
        var checks = 0
        LinkReliabilityLab.run(
            input = RealCanFixture.load(javaClass),
            shouldContinue = { ++checks < 200 },
        )
    }
}
