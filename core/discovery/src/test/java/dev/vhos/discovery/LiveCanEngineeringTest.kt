package dev.vhos.discovery

import dev.vhos.model.PersistedLiveCanObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCanEngineeringTest {
    @Test
    fun freshPersistedRealCanProjectsPinnedUnverifiedUnitsAndRawChannels() {
        val fixture = RealCanFixture.load(javaClass)
        val rpm = fixture.last { it.observation.identifier == 0x2C4u }
        val raw420 = fixture.last { it.observation.identifier == 0x420u }
        val pack = SignalHypothesisCatalog.loadBundled()
        val now = 100_000L

        val projection = LiveCanEngineeringProjector.project(
            input = listOf(
                rpm.toLive(receivedAtEpochMs = now - 100L),
                raw420.toLive(receivedAtEpochMs = now - 80L).copy(
                    firstObservedAtEpochMs = now - 480L,
                    firstSourceSequence = raw420.observation.sourceSequence - 4UL,
                    firstGatewayMonotonicMicroseconds =
                        raw420.observation.monotonicMicroseconds - 400_000UL,
                    observationCount = 5L,
                    payloadChangeCount = 3L,
                    latestChangedByteMask = 0x04,
                ),
            ),
            pack = pack,
            nowEpochMs = now,
        )

        assertEquals(LiveCanEngineeringProjector.STATUS_LIVE, projection.status)
        assertEquals(2, projection.freshSamples)
        val engine = projection.values.single {
            it.candidateId == "toyota.2c4.engine-speed.be16"
        }
        val expectedRaw = rpm.observation.data[0].toUByte().toInt() * 256.0 +
            rpm.observation.data[1].toUByte().toInt()
        assertEquals(expectedRaw, engine.rawValue, 0.0)
        assertEquals(expectedRaw * 0.78125, engine.value, 0.0)
        assertEquals("rpm", engine.unit)
        assertEquals(rpm.observation.sourceSequence, engine.sourceSequence)
        assertEquals(SignalHypothesisEvaluator.REQUIRED_BADGE, engine.requiredBadge)
        assertEquals(LiveCanEngineeringValue.AUTHORITY, engine.authority)
        assertTrue(engine.transformFormula.contains("0.78125"))

        val temperature = projection.values.filter {
            it.candidateId == "toyota.2c4.intake-air-temperature.byte3"
        }
        assertEquals(2, temperature.size)
        assertEquals(setOf("degC"), temperature.map { it.unit }.toSet())
        assertTrue(temperature.all { it.competingTransformCount == 2 })

        val raw = projection.rawChannels.single { it.identifier == 0x420u }
        assertFalse(raw.dataHex.isBlank())
        assertEquals(raw420.observation.dataLength, raw.dataLength)
        assertEquals(5L, raw.observationCount)
        assertEquals(3L, raw.payloadChangeCount)
        assertEquals(listOf(2), raw.latestChangedByteIndices)
        assertEquals(10.0, requireNotNull(raw.updateRateHz), 0.0)
        assertEquals(LiveCanRawChannel.AUTHORITY, raw.authority)
    }

    @Test
    fun everyFreshObservedIdentifierAppearsWithoutAnIdentifierAllowlist() {
        val latestByIdentifier = RealCanFixture.load(javaClass)
            .groupBy { it.observation.identifier to it.observation.extended }
            .values
            .map { values -> values.maxBy { it.observation.monotonicMicroseconds } }
        val now = 120_000L

        val projection = LiveCanEngineeringProjector.project(
            input = latestByIdentifier.mapIndexed { index, item ->
                item.toLive(receivedAtEpochMs = now - index)
            },
            pack = SignalHypothesisCatalog.loadBundled(),
            nowEpochMs = now,
        )

        assertEquals(17, latestByIdentifier.size)
        assertEquals(
            latestByIdentifier.map { it.observation.identifier }.toSet(),
            projection.rawChannels.map { it.identifier }.toSet(),
        )
        assertTrue(
            setOf(0x022u, 0x023u, 0x223u, 0x224u, 0x3D0u, 0x420u)
                .all { identifier -> projection.rawChannels.any { it.identifier == identifier } }
        )
        assertEquals(latestByIdentifier.size, projection.freshSamples)

        val pinnedIdentifiers = projection.values.map { it.identifier }.toSet()
        assertTrue(pinnedIdentifiers.isNotEmpty())
        assertTrue(
            projection.rawChannels
                .filter { it.identifier in pinnedIdentifiers }
                .all { it.hasPinnedPhysicalProjection }
        )
    }

    @Test
    fun dlcSevenPayloadRemainsExactOnItsGenericOrPinnedLiveCard() {
        val source = RealCanFixture.load(javaClass)
            .last { it.observation.identifier == 0x023u }
        assertEquals(7, source.observation.dataLength)
        val projection = LiveCanEngineeringProjector.project(
            input = listOf(source.toLive(receivedAtEpochMs = 130_000L)),
            pack = SignalHypothesisCatalog.loadBundled(),
            nowEpochMs = 130_050L,
        )
        val expectedHex = source.observation.data.take(7).joinToString(" ") {
            String.format(java.util.Locale.US, "%02X", it.toUByte().toInt())
        }
        val candidate = projection.values.firstOrNull { it.identifier == 0x023u }
        val raw = projection.rawChannels.single { it.identifier == 0x023u }

        assertEquals(7, raw.dataLength)
        assertEquals(expectedHex, raw.dataHex)
        candidate?.let {
            assertEquals(7, it.dataLength)
            assertEquals(expectedHex, it.dataHex)
        }
    }

    @Test
    fun staleOrMissingRuntimeEvidenceNeverProducesAZeroValue() {
        val fixture = RealCanFixture.load(javaClass)
        val rpm = fixture.last { it.observation.identifier == 0x2C4u }
        val pack = SignalHypothesisCatalog.loadBundled()
        val stale = LiveCanEngineeringProjector.project(
            input = listOf(rpm.toLive(receivedAtEpochMs = 10_000L)),
            pack = pack,
            nowEpochMs = 20_001L,
            freshnessMillis = 5_000L,
        )
        assertEquals(LiveCanEngineeringProjector.STATUS_UNAVAILABLE, stale.status)
        assertTrue(stale.values.isEmpty())
        assertTrue(stale.rawChannels.isEmpty())
        assertEquals(1, stale.staleSamples)

        val staleUi = LiveCanUnitsUiModelProjector.project(stale)
        assertTrue(staleUi.valueRows.isEmpty())
        assertTrue(staleUi.rawRows.isEmpty())
        assertTrue(requireNotNull(staleUi.emptyReason).contains("stale"))

        val missing = LiveCanEngineeringProjector.project(
            input = emptyList(),
            pack = pack,
            nowEpochMs = 20_001L,
        )
        val missingUi = LiveCanUnitsUiModelProjector.project(missing)
        assertTrue(missingUi.valueRows.isEmpty())
        assertNotNull(missingUi.emptyReason)
        assertTrue(requireNotNull(missingUi.emptyReason).contains("No persisted live"))
    }

    @Test
    fun uiModelKeepsUnitsFormulaSequenceFreshnessAndAuthorityVisible() {
        val rpm = RealCanFixture.load(javaClass)
            .last { it.observation.identifier == 0x2C4u }
        val now = 100_000L
        val projection = LiveCanEngineeringProjector.project(
            input = listOf(rpm.toLive(receivedAtEpochMs = now - 125L)),
            pack = SignalHypothesisCatalog.loadBundled(),
            nowEpochMs = now,
        )

        val row = LiveCanUnitsUiModelProjector.project(projection).valueRows.single {
            it.title.startsWith("engine.speed")
        }
        assertTrue(row.valueText.endsWith(" rpm"))
        assertTrue(row.evidenceText.contains("seq ${rpm.observation.sourceSequence}"))
        assertTrue(row.evidenceText.contains("age 125 ms"))
        assertTrue(row.payloadText.startsWith("DLC 8"))
        assertTrue(row.changeText.contains("payload changes 0"))
        assertTrue(row.formulaText.contains("0.78125"))
        assertEquals(SignalHypothesisEvaluator.REQUIRED_BADGE, row.badge)
        assertEquals(LiveCanEngineeringValue.AUTHORITY, row.authority)
    }

    @Test
    fun rawUiShowsExactIdentityPayloadFreshnessAppObservedRateAndCompactChange() {
        val source = RealCanFixture.load(javaClass)
            .last { it.observation.identifier == 0x420u }
        val now = 140_000L
        val sample = source.toLive(receivedAtEpochMs = now - 50L).copy(
            firstObservedAtEpochMs = now - 1_050L,
            firstSourceSequence = source.observation.sourceSequence - 10UL,
            firstGatewayMonotonicMicroseconds =
                source.observation.monotonicMicroseconds - 1_000_000UL,
            observationCount = 11L,
            payloadChangeCount = 4L,
            latestChangedByteMask = 0x05,
        )
        val ui = LiveCanUnitsUiModelProjector.project(
            LiveCanEngineeringProjector.project(
                input = listOf(sample),
                pack = SignalHypothesisCatalog.loadBundled(),
                nowEpochMs = now,
            )
        )
        val row = ui.rawRows.single()

        assertEquals("0x420 • RAW ONLY", row.title)
        assertTrue(row.payloadText.startsWith("DLC ${source.observation.dataLength} • "))
        assertTrue(row.evidenceText.contains("seq ${source.observation.sourceSequence}"))
        assertTrue(row.evidenceText.contains("age 50 ms"))
        assertTrue(row.evidenceText.contains("app-persisted 11"))
        assertTrue(row.evidenceText.contains("app-observed avg 10.00 Hz"))
        assertEquals("payload changes 4 • latest Δ bytes 0,2", row.changeText)
    }

    @Test
    fun projectorRejectsMixedCaptureSessions() {
        val source = RealCanFixture.load(javaClass)
            .last { it.observation.identifier == 0x420u }
        val first = source.toLive(receivedAtEpochMs = 150_000L)
        val second = source.toLive(receivedAtEpochMs = 150_001L).copy(
            identifier = 0x421u,
            sessionId = first.sessionId + 1u,
        )

        assertThrows(IllegalArgumentException::class.java) {
            LiveCanEngineeringProjector.project(
                input = listOf(first, second),
                pack = SignalHypothesisCatalog.loadBundled(),
                nowEpochMs = 150_100L,
            )
        }
    }

    private fun DiscoveryObservation.toLive(receivedAtEpochMs: Long) =
        PersistedLiveCanObservation(
            sourceId = sourceId,
            receivedAtEpochMs = receivedAtEpochMs,
            sessionId = observation.sessionId,
            sourceSequence = observation.sourceSequence,
            gatewayMonotonicMicroseconds = observation.monotonicMicroseconds,
            bitrateBps = observation.bitrateBps,
            identifier = observation.identifier,
            extended = observation.extended,
            dataLength = observation.dataLength,
            data = observation.data.map { it.toUByte().toInt() },
        )
}
