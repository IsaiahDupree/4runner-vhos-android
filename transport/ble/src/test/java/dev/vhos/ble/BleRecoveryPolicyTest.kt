package dev.vhos.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRecoveryPolicyTest {
    @Test
    fun mapsAndroidScanFailuresToStableEvidenceNames() {
        assertEquals("SCAN_FAILED_ALREADY_STARTED", BleRecoveryPolicy.scanErrorName(1))
        assertEquals("SCAN_FAILED_APPLICATION_REGISTRATION_FAILED", BleRecoveryPolicy.scanErrorName(2))
        assertEquals("SCAN_FAILED_INTERNAL_ERROR", BleRecoveryPolicy.scanErrorName(3))
        assertEquals("SCAN_FAILED_FEATURE_UNSUPPORTED", BleRecoveryPolicy.scanErrorName(4))
        assertEquals("SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES", BleRecoveryPolicy.scanErrorName(5))
        assertEquals("SCAN_FAILED_SCANNING_TOO_FREQUENTLY", BleRecoveryPolicy.scanErrorName(6))
        assertEquals("SCAN_FAILED_UNKNOWN", BleRecoveryPolicy.scanErrorName(99))
    }

    @Test
    fun internalStackFailuresUseAColdExponentialBackoff() {
        assertEquals(15_000L, BleRecoveryPolicy.afterScanFailure(3, 1).delayMillis)
        assertEquals(30_000L, BleRecoveryPolicy.afterScanFailure(3, 2).delayMillis)
        assertEquals(60_000L, BleRecoveryPolicy.afterScanFailure(3, 3).delayMillis)
        assertEquals(120_000L, BleRecoveryPolicy.afterScanFailure(3, 4).delayMillis)
        assertFalse(BleRecoveryPolicy.afterScanFailure(3, 5).automatic)
    }

    @Test
    fun unsupportedScanFeatureRequiresAnOwnerRetry() {
        val decision = BleRecoveryPolicy.afterScanFailure(4, 1)
        assertFalse(decision.automatic)
        assertEquals(0L, decision.delayMillis)
    }

    @Test
    fun noResultWindowsAreBoundedAndNeverBecomeAHotLoop() {
        assertEquals(5_000L, BleRecoveryPolicy.afterNoResult(1).delayMillis)
        assertEquals(15_000L, BleRecoveryPolicy.afterNoResult(2).delayMillis)
        assertEquals(30_000L, BleRecoveryPolicy.afterNoResult(3).delayMillis)
        assertEquals(60_000L, BleRecoveryPolicy.afterNoResult(4).delayMillis)
        assertFalse(BleRecoveryPolicy.afterNoResult(5).automatic)
        assertTrue(BleRecoveryPolicy.SCAN_WINDOW_MILLIS >= 10_000L)
    }

    @Test
    fun repeatedConnectionLossesEscalateAndThenOpenTheCircuit() {
        assertEquals(5_000L, BleRecoveryPolicy.afterConnectionLoss(1).delayMillis)
        assertEquals(15_000L, BleRecoveryPolicy.afterConnectionLoss(2).delayMillis)
        assertEquals(30_000L, BleRecoveryPolicy.afterConnectionLoss(3).delayMillis)
        assertEquals(60_000L, BleRecoveryPolicy.afterConnectionLoss(4).delayMillis)

        val exhausted = BleRecoveryPolicy.afterConnectionLoss(5)
        assertFalse(exhausted.automatic)
        assertEquals(0L, exhausted.delayMillis)
    }
}
