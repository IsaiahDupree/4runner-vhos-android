package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalResearchPlannerTest {
    @Test
    fun realEvidenceProducesRankedFailClosedResearchMissions() {
        val records = RealCanFixture.load(javaClass)
        val pack = SignalHypothesisCatalog.loadBundled()
        val discovery = CanDiscoveryAnalyzer.analyze(records)
        val evaluation = SignalHypothesisEvaluator.evaluate(records, pack)

        val brief = SignalResearchPlanner.plan(discovery, evaluation, pack)

        assertEquals("ENGINEERING_RESEARCH_PLAN", brief.status)
        assertEquals("0.4.0", brief.packVersion)
        assertEquals(11, brief.candidateCount)
        assertEquals(11, brief.presentCount)
        assertTrue(brief.dynamicFieldCount >= 1)
        assertTrue(brief.independentReferenceReadyCount >= 1)
        assertFalse(brief.ownerDisplayAllowed)
        assertTrue(brief.missions.all { !it.ownerDisplayAllowed })
        assertEquals((1..brief.missions.size).toList(), brief.missions.map { it.rank })
        assertTrue(brief.missions.zipWithNext().all { (left, right) ->
            left.researchPriority >= right.researchPriority
        })

        val engine = brief.missions.single { it.hypothesisId == "toyota.2c4.engine-speed.be16" }
        assertEquals("INDEPENDENT_REFERENCE_READY", engine.stage)
        assertTrue(engine.primaryResearchSourceCount >= 3)
        assertTrue(engine.reasons.any { it.contains("class-B primary research sources") })
        assertTrue(engine.nextValidation.contains("PID 0x0C"))

        val intake = brief.missions.single {
            it.hypothesisId == "toyota.2c4.intake-air-temperature.byte3"
        }
        assertEquals("CONTROLLED_EXCITATION_REQUIRED", intake.stage)
        assertTrue(intake.blockers.any { it.contains("transforms conflict") })

        val brake = brief.missions.single {
            it.hypothesisId == "toyota.224.brake-pressure.be16-low9"
        }
        assertEquals("CONTROLLED_EXCITATION_REQUIRED", brake.stage)

        val unknown = brief.missions.single { it.hypothesisId == "toyota.420.unknown.id-only" }
        assertEquals("UNMAPPED_RESEARCH", unknown.stage)
        assertTrue(unknown.blockers.any { it.contains("No physical semantic") })

        assertTrue(
            brief.missions.take(4).map { it.hypothesisId }.containsAll(
                listOf("toyota.2c4.engine-speed.be16", "toyota.2c1.accelerator-pedal.byte6")
            )
        )
    }

    @Test
    fun plannerRejectsMismatchedPackLineage() {
        val records = RealCanFixture.load(javaClass)
        val pack = SignalHypothesisCatalog.loadBundled()
        val discovery = CanDiscoveryAnalyzer.analyze(records)
        val evaluation = SignalHypothesisEvaluator.evaluate(records, pack).copy(
            packSha256 = "0".repeat(64),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            SignalResearchPlanner.plan(discovery, evaluation, pack)
        }

        assertTrue(error.message.orEmpty().contains("lineage"))
    }
}
