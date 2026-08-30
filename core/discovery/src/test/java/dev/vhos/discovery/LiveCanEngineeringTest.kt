package dev.vhos.discovery

import dev.vhos.model.PersistedLiveCanObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                raw420.toLive(receivedAtEpochMs = now - 80L),
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

        val raw = projection.rawOnlyChannels.single { it.identifier == 0x420u }
        assertFalse(raw.dataHex.isBlank())
        assertEquals(LiveCanRawChannel.AUTHORITY, raw.authority)
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
        assertTrue(stale.rawOnlyChannels.isEmpty())
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
        assertTrue(row.formulaText.contains("0.78125"))
        assertEquals(SignalHypothesisEvaluator.REQUIRED_BADGE, row.badge)
        assertEquals(LiveCanEngineeringValue.AUTHORITY, row.authority)
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
