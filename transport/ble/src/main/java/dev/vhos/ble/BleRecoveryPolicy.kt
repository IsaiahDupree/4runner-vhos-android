package dev.vhos.ble

import dev.vhos.model.DeviceRole

data class BleRecoveryDecision(
    val automatic: Boolean,
    val delayMillis: Long,
)

enum class BleScanStrategy {
    SERVICE_FILTERED,
    SOFTWARE_QUALIFIED,
}

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

    /**
     * Some vendor Bluetooth stacks fail while registering an Android hardware/service filter even
     * though an ordinary BLE scan remains usable. The compatibility strategy removes only that
     * platform filter. Candidate admission remains restricted to the VHOS service UUID or an
     * approved VHOS owner-facing name, and GATT/CRC/handshake validation is still mandatory.
     */
    fun nextScanStrategyAfterFailure(
        current: BleScanStrategy,
        errorCode: Int,
    ): BleScanStrategy = when {
        current == BleScanStrategy.SERVICE_FILTERED && errorCode in setOf(3, 4, 5) ->
            BleScanStrategy.SOFTWARE_QUALIFIED
        else -> current
    }

    fun nextScanStrategyAfterNoResult(current: BleScanStrategy): BleScanStrategy = when (current) {
        BleScanStrategy.SERVICE_FILTERED -> BleScanStrategy.SOFTWARE_QUALIFIED
        BleScanStrategy.SOFTWARE_QUALIFIED -> BleScanStrategy.SOFTWARE_QUALIFIED
    }

    fun approvedRole(advertisedName: String?): DeviceRole? {
        val normalized = advertisedName?.trim()?.uppercase() ?: return null
        return when {
            normalized.startsWith("VHOS-4R-OBD") || normalized.startsWith("VHOS-MRDIY-") ->
                DeviceRole.OBD_CAN
            normalized.startsWith("VHOS-4R-AC") || normalized.startsWith("VHOS-AC-") ->
                DeviceRole.AC_SENSOR
            else -> null
        }
    }

    fun admitsAdvertisement(
        strategy: BleScanStrategy,
        advertisedName: String?,
        advertisesVhosService: Boolean,
    ): Boolean = when (strategy) {
        // Android has already applied the exact UUID filter before delivering the callback.
        BleScanStrategy.SERVICE_FILTERED -> true
        BleScanStrategy.SOFTWARE_QUALIFIED ->
            advertisesVhosService || approvedRole(advertisedName) != null
    }

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
