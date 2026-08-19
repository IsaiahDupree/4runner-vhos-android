package dev.vhos.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalHypothesisEvaluatorTest {
    @Test
    fun bundledPackIsHashPinnedDiscoveryOnlyAndEvaluatesRealEvidence() {
        val pack = SignalHypothesisCatalog.loadBundled()
        val records = RealCanFixture.load(javaClass)

        val report = SignalHypothesisEvaluator.evaluate(records, pack)

        assertEquals("toyota.4runner.2005.passive-can-hypotheses", report.packId)
        assertEquals("0.3.0", report.packVersion)
        assertEquals(SignalHypothesisCatalog.BUNDLED_SHA256, report.packSha256)
        assertEquals(11, pack.hypothesisCount)
        assertEquals("DISCOVERY_ONLY", report.status)
        assertEquals(0, report.acceptedSignalDefinitions)
        assertFalse(report.promotionAllowed)
        assertTrue(report.evaluations.all { !it.productionValueDisplayAllowed })

        val engine = report.evaluations.single {
            it.hypothesisId == "toyota.2c4.engine-speed.be16"
        }
        assertTrue(engine.records > 0)
        assertEquals("FIELD_PRESENT_DYNAMIC", engine.targetEvidenceStatus)
        assertEquals("rpm", engine.transformEvaluations.single().unit)
        assertTrue(engine.requiredValidation.any { it.contains("PID 0x0C") })

        val steering = report.evaluations.single {
            it.hypothesisId == "toyota.025.steering-angle.signed12"
        }
        assertTrue(steering.records > 0)
        assertEquals(2, steering.transformEvaluations.size)
        assertTrue(steering.limitations.contains("not a universal layout", ignoreCase = true))
        assertTrue(steering.limitations.contains("No target degree value is accepted"))
    }

    @Test
    fun parserRejectsAnyPackThatClaimsAcceptedVehicleAuthority() {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            SignalHypothesisCatalog.BUNDLED_RESOURCE
        )).use { it.readBytes() }
        val unsafe = raw.toString(Charsets.UTF_8).replace(
            "\"accepted_signal_definitions\": 0",
            "\"accepted_signal_definitions\": 1",
        ).toByteArray()

        val error = assertThrows(IllegalArgumentException::class.java) {
            SignalHypothesisCatalog.parse(unsafe)
        }

        assertTrue(error.message.orEmpty().contains("accepted signal definitions"))
    }

    @Test
    fun evaluatorRejectsEvidenceWithoutListenOnlyProof() {
        val pack = SignalHypothesisCatalog.loadBundled()
        val valid = RealCanFixture.load(javaClass).first()
        val unsafe = valid.copy(
            observation = valid.observation.copy(listenOnly = false),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            SignalHypothesisEvaluator.evaluate(listOf(unsafe), pack)
        }

        assertTrue(error.message.orEmpty().contains("listen-only"))
    }
}
