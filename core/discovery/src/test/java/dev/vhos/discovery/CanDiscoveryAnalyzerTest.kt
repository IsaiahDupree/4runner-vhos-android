package dev.vhos.discovery

import dev.vhos.protocol.CanObservation
import org.junit.Assert.assertEquals
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
