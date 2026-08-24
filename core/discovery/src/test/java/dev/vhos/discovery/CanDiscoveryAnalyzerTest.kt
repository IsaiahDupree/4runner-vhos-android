package dev.vhos.discovery

import dev.vhos.protocol.CanObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanDiscoveryAnalyzerTest {
    @Test
    fun reportsRawRelationshipsWithoutAssigningVehicleMeaning() {
        val records = buildList {
            repeat(12) { index ->
                val first = 1_360 + index
                val second = first * 2
                val timestamp = 1_000_000UL + index.toULong() * 200_000UL
                add(observation(0x2C4u, first, 1UL + index.toULong() * 15UL, timestamp))
                add(observation(0x2D0u, second, 2UL + index.toULong() * 15UL, timestamp + 10_000UL))
                add(repeatedObservation(3UL + index.toULong() * 15UL, timestamp + 20_000UL, 120 + index % 4))
            }
        }

        val report = CanDiscoveryAnalyzer.analyze(records)

        assertEquals("1.0.0", report.contractVersion)
        assertEquals("DISCOVERY_CANDIDATE", report.status)
        assertEquals(36, report.acquisition.records)
        assertEquals(3, report.acquisition.uniqueIdentifiers)
        assertEquals(36, report.acquisition.listenOnlyRecords)
        assertEquals(listOf(500_000), report.acquisition.bitratesBps)
        assertEquals(3, report.identifierActivity.count { it.checksum.candidate })
        val relation = report.rawWordRelationships.single {
            it.leftIdentifier == 0x2C4u && it.rightIdentifier == 0x2D0u
        }
        assertEquals(1.0, relation.pearsonCorrelation, 0.000_001)
        assertEquals(2.0, relation.medianRightToLeftRatio!!, 0.000_001)
        assertEquals(24, relation.provenance.localAuthorizedRecords)
        val repeated = report.repeatedChannels.single { it.identifier == 0x025u }
        assertEquals(listOf(4, 5, 6), repeated.bytePositions)
        assertTrue(report.authority.contains("no identifier"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnyObservationWithoutListenOnlyProof() {
        val valid = observation(0x2C4u, 1_360, 1UL, 1_000_000UL)
        val unsafe = valid.copy(observation = valid.observation.copy(listenOnly = false))
        CanDiscoveryAnalyzer.analyze(listOf(unsafe))
    }

    @Test
    fun keepsHistoricalAuthorityClassesDistinctInAnalysis() {
        val base = observation(0x2C4u, 1_360, 1UL, 1_000_000UL)
        val report = CanDiscoveryAnalyzer.analyze(
            listOf(
                base,
                base.copy(
                    observation = base.observation.copy(sourceSequence = 2UL, monotonicMicroseconds = 2_000_000UL),
                    provenance = DiscoveryEvidenceProvenance.IMPORTED_V1_HISTORY,
                ),
                base.copy(
                    observation = base.observation.copy(sourceSequence = 3UL, monotonicMicroseconds = 3_000_000UL),
                    provenance = DiscoveryEvidenceProvenance.RECOVERED_V2_HISTORY,
                ),
                base.copy(
                    observation = base.observation.copy(sourceSequence = 4UL, monotonicMicroseconds = 4_000_000UL),
                    provenance = DiscoveryEvidenceProvenance.AMBIGUOUS_LEGACY_HISTORY,
                ),
            )
        )

        assertEquals(1, report.acquisition.provenance.localAuthorizedRecords)
        assertEquals(1, report.acquisition.provenance.importedV1Records)
        assertEquals(1, report.acquisition.provenance.recoveredV2Records)
        assertEquals(1, report.acquisition.provenance.ambiguousLegacyRecords)
        assertEquals(4, report.identifierActivity.single().provenance.totalRecords)
    }

    @Test
    fun deterministicCorrelationBoundsAndCancellationFailClosed() {
        val records = buildList {
            repeat(12) { index ->
                add(observation(0x2C4u, 1_360 + index, index.toULong() * 2UL + 1UL, index.toULong() * 2_000UL))
                add(observation(0x2D0u, 2_720 + index, index.toULong() * 2UL + 2UL, index.toULong() * 2_000UL + 10UL))
            }
        }
        val bounded = assertThrows(IllegalArgumentException::class.java) {
            CanDiscoveryAnalyzer.analyze(
                records,
                limits = DiscoveryAnalysisLimits(maximumEligibleIdentifiers = 2, maximumCorrelationPairs = 1,
                    maximumPairedSamplesPerCorrelation = 10),
            )
        }
        assertTrue(bounded.message.orEmpty().contains("sample count"))

        var calls = 0
        assertThrows(DiscoveryAnalysisCancelledException::class.java) {
            CanDiscoveryAnalyzer.analyze(records, shouldContinue = { calls++ < 3 })
        }
    }

    @Test
    fun rejectsUnsignedSequenceAndMonotonicClockRegressionInsteadOfWrapping() {
        val first = observation(0x2C4u, 1_360, ULong.MAX_VALUE, 1_000UL)
        val wrappedSequence = observation(0x2C4u, 1_361, 0UL, 2_000UL)
        assertThrows(IllegalArgumentException::class.java) {
            CanDiscoveryAnalyzer.analyze(listOf(first, wrappedSequence))
        }

        val monotonicRegression = observation(0x2C4u, 1_361, ULong.MAX_VALUE - 1UL, 2_000UL)
        assertThrows(IllegalArgumentException::class.java) {
            CanDiscoveryAnalyzer.analyze(listOf(first, monotonicRegression))
        }
    }

    private fun observation(
        identifier: UInt,
        value: Int,
        sequence: ULong,
        timestamp: ULong,
    ): DiscoveryObservation {
        val values = listOf(value shr 8, value and 0xFF, 0, 0, 0, 0, 0)
        return record(identifier, sequence, timestamp, withChecksum(identifier, values))
    }

    private fun repeatedObservation(sequence: ULong, timestamp: ULong, value: Int): DiscoveryObservation =
        record(0x025u, sequence, timestamp, withChecksum(0x025u, listOf(0, 0, 0, 0, value, value, value)))

    private fun withChecksum(identifier: UInt, values: List<Int>): List<Int> {
        val checksum = (((identifier.toInt() shr 8) and 0xFF) +
            (identifier.toInt() and 0xFF) + values.size + 1 + values.sum()) and 0xFF
        return values + checksum
    }

    private fun record(
        identifier: UInt,
        sequence: ULong,
        timestamp: ULong,
        payload: List<Int>,
    ) = DiscoveryObservation(
        sourceId = "esp32-9454c5b08d14",
        provenance = DiscoveryEvidenceProvenance.LOCAL_AUTHORIZED,
        observation = CanObservation(
            sessionId = 740_616_386u,
            sourceSequence = sequence,
            monotonicMicroseconds = timestamp,
            bitrateBps = 500_000,
            identifier = identifier,
            extended = false,
            remoteRequest = false,
            listenOnly = true,
            dataLength = payload.size,
            data = ByteArray(8) { index -> payload.getOrElse(index) { 0 }.toByte() },
        ),
    )
}
