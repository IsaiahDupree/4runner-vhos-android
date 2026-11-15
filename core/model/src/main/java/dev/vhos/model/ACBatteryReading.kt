package dev.vhos.model

enum class ACBatteryResult {
    VALID,
    GAUGE_IO,
    MODEL,
    RANGE,
    PACK_TEMPERATURE_UNAVAILABLE,
}

data class ACBatteryReading(
    val result: ACBatteryResult,
    val volts: Double?,
    val milliamps: Double?,
    val averageMilliamps: Double?,
    val stateOfCharge: Double?,
    val remainingMilliampHours: Double?,
    val fullMilliampHours: Double?,
    val healthPercent: Double?,
    val equivalentCycles: Double?,
    val secondsToEmpty: Double?,
    val secondsToFull: Double?,
    val packCelsius: Double?,
) {
    val signedWatts: Double? get() = volts?.let { value -> milliamps?.let { value * it / 1000.0 } }
}
