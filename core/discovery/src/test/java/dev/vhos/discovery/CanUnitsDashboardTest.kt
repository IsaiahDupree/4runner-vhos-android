package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanUnitsDashboardTest {
    @Test
    fun realCaptureProjectsPhysicalCandidateUnitsRawChannelsAndDerivedRelationshipWithoutPromotion() {
        val records = RealCanFixture.load(javaClass)
        val pack = SignalHypothesisCatalog.loadBundled()
        val discovery = CanDiscoveryAnalyzer.analyze(records)
        val evaluation = SignalHypothesisEvaluator.evaluate(records, pack)

        val dashboard = CanUnitsDashboardProjector.project(discovery, evaluation)

        assertEquals("ENGINEERING_HISTORICAL_UNITS", dashboard.status)
        assertEquals("0.4.1", dashboard.packVersion)
        assertEquals(SignalHypothesisCatalog.BUNDLED_SHA256, dashboard.packSha256)
        assertEquals(SignalHypothesisEvaluator.REQUIRED_BADGE, dashboard.requiredBadge)
        assertTrue(dashboard.standardizedValuesAuthority.contains("J1979"))

        val engine = dashboard.candidateUnitSeries.single {
            it.candidateId == "toyota.2c4.engine-speed.be16"
        }
        assertEquals("rpm", engine.transform.unit)
        assertEquals(0.78125, engine.transform.scale, 0.0)
        assertEquals(0.0, engine.transform.offset, 0.0)
        assertTrue(engine.transform.formula.contains("payload[0..1]"))
        assertTrue(engine.transform.summary.count > 10)
        assertTrue(engine.transform.summary.peakToPeak > 0.0)
        assertNotNull(engine.transform.summary.coefficientOfVariation)
        assertEquals(engine.records, engine.provenance.totalRecords)
        assertEquals(CanCandidateUnitSeries.AUTHORITY, engine.authority)

        val raw420 = dashboard.rawOnlyChannels.single { it.identifier == 0x420u }
        assertEquals(CanRawOnlyChannel.AUTHORITY, raw420.authority)
        assertTrue(raw420.records > 0)
        assertFalse(raw420.dynamicBytePositions.isEmpty())

        val rotational = dashboard.derivedRelationships.single {
            it.relationshipId == "candidate-engine-rpm-vs-turbine-rpm"
        }
        assertEquals("rpm", rotational.commonCandidateUnit)
        assertTrue(rotational.pairedSamples >= 10)
        assertTrue(rotational.pearsonCorrelation > 0.95)
        assertNotNull(rotational.medianRawRightToLeftRatio)
        assertNotNull(rotational.medianCandidateUnitRightToLeftRatio)
        assertEquals(CanDerivedRelationship.AUTHORITY, rotational.authority)
        assertTrue(rotational.authority.contains("not identity"))
    }

    @Test
    fun candidateAdapterRetainsRawStatisticsTransformsFormulaSourcesAndLimitations() {
        val records = RealCanFixture.load(javaClass)
        val pack = SignalHypothesisCatalog.loadBundled()
        val discovery = CanDiscoveryAnalyzer.analyze(records)
        val evaluation = SignalHypothesisEvaluator.evaluate(records, pack)
        val research = SignalResearchPlanner.plan(discovery, evaluation, pack)

        val item = AndroidCandidateResearchAdapter.from(evaluation, research).single {
            it.candidateId == "toyota.2c1.accelerator-pedal.byte6"
        }

        assertNotNull(item.rawFieldValues)
        assertNotNull(item.fieldFormula)
        assertEquals("percent", item.candidateTransforms.single().unit)
        assertTrue(item.candidateTransforms.single().formula.contains("0.5"))
        assertFalse(item.sourceIds.isEmpty())
        assertFalse(item.limitations.isBlank())
        assertFalse(item.promotionChecklist.ready)
        assertEquals(null, item.confidence)
    }
}
