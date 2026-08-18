package dev.vhos.ble

data class BleRecoveryDecision(
    val automatic: Boolean,
    val delayMillis: Long,
)

object BleRecoveryPolicy {
    const val SCAN_WINDOW_MILLIS = 12_000L
    const val GATT_CONNECT_TIMEOUT_MILLIS = 15_000L
    const val MTU_NEGOTIATION_TIMEOUT_MILLIS = 3_000L
    const val SERVICE_DISCOVERY_TIMEOUT_MILLIS = 12_000L
    const val SECURE_SUBSCRIPTION_TIMEOUT_MILLIS = 30_000L
    const val HANDSHAKE_TIMEOUT_MILLIS = 15_000L
    const val MAX_AUTOMATIC_RECOVERY_ATTEMPTS = 4

    private val normalBackoffMillis = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)
    private val stackBackoffMillis = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    fun scanErrorName(errorCode: Int): String = when (errorCode) {
        1 -> "SCAN_FAILED_ALREADY_STARTED"
        2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        3 -> "SCAN_FAILED_INTERNAL_ERROR"
        4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
        else -> "SCAN_FAILED_UNKNOWN"
    }

    fun afterScanFailure(errorCode: Int, consecutiveFailure: Int): BleRecoveryDecision {
        require(consecutiveFailure >= 1)
        if (errorCode == 4 || consecutiveFailure > MAX_AUTOMATIC_RECOVERY_ATTEMPTS) {
            return BleRecoveryDecision(automatic = false, delayMillis = 0L)
        }
        val backoff = when (errorCode) {
            2, 3, 5, 6 -> stackBackoffMillis
            else -> normalBackoffMillis
        }
        return BleRecoveryDecision(
            automatic = true,
            delayMillis = backoff[(consecutiveFailure - 1).coerceAtMost(backoff.lastIndex)],
        )
    }

    fun afterNoResult(consecutiveFailure: Int): BleRecoveryDecision =
        bounded(normalBackoffMillis, consecutiveFailure)

    fun afterConnectionLoss(consecutiveFailure: Int): BleRecoveryDecision =
        bounded(normalBackoffMillis, consecutiveFailure)

    private fun bounded(backoff: LongArray, consecutiveFailure: Int): BleRecoveryDecision {
        require(consecutiveFailure >= 1)
        if (consecutiveFailure > MAX_AUTOMATIC_RECOVERY_ATTEMPTS) {
            return BleRecoveryDecision(automatic = false, delayMillis = 0L)
        }
        return BleRecoveryDecision(
            automatic = true,
            delayMillis = backoff[(consecutiveFailure - 1).coerceAtMost(backoff.lastIndex)],
        )
    }
}
