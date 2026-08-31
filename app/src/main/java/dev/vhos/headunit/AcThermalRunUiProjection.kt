package dev.vhos.headunit

import dev.vhos.model.AcThermalRunPhase
import dev.vhos.model.AcThermalRunSnapshot
import dev.vhos.model.IndicatorLevel
import java.util.Locale

internal data class AcThermalRunUiState(
    val badge: String,
    val primaryMetric: String,
    val temperatures: String,
    val detail: String,
    val level: IndicatorLevel,
)

internal fun acThermalRunUiState(run: AcThermalRunSnapshot): AcThermalRunUiState {
    val channel = run.targetChannel.displayName.uppercase(Locale.US)
    return when (run.phase) {
        AcThermalRunPhase.IDLE -> AcThermalRunUiState(
            badge = "READY TO ARM",
            primaryMetric = "TIME TO MAX  —",
            temperatures = "Start —  •  Current —  •  Maximum —",
            detail = "Start a run before changing the A/C state. The first qualified $channel sample becomes the start temperature.",
            level = IndicatorLevel.WAIT,
        )

        AcThermalRunPhase.ARMED -> AcThermalRunUiState(
            badge = "ARMED • WAITING FOR SENSOR",
            primaryMetric = "TIME TO MAX  —",
            temperatures = "Start —  •  Current —  •  Maximum —",
            detail = run.lastLimitation ?: "Waiting for qualified $channel evidence.",
            level = IndicatorLevel.ACTIVE,
        )

        AcThermalRunPhase.RUNNING -> AcThermalRunUiState(
            badge = "LIVE • PROVISIONAL",
            primaryMetric = "TIME TO MAX  ${formatAcDuration(run.timeToMaximumMillis)}",
            temperatures = temperatureSummary(run),
            detail = "${run.acceptedSamples} qualified samples • elapsed ${formatAcDuration(run.elapsedMillis)}. End the run to freeze the result.",
            level = IndicatorLevel.ACTIVE,
        )

        AcThermalRunPhase.COMPLETE -> AcThermalRunUiState(
            badge = "COMPLETE • NOT A HEALTH VERDICT",
            primaryMetric = "TIME TO MAX  ${formatAcDuration(run.timeToMaximumMillis)}",
            temperatures = temperatureSummary(run),
            detail = "Final for this capture: ${run.acceptedSamples} qualified samples. Result ${AcThermalRunSnapshot.CALCULATION_ID} uses the sensor monotonic clock.",
            level = IndicatorLevel.CHECK,
        )

        AcThermalRunPhase.INVALID -> AcThermalRunUiState(
            badge = "INVALID • RESULT WITHHELD",
            primaryMetric = "TIME TO MAX  —",
            temperatures = "Start —  •  Current —  •  Maximum —",
            detail = run.invalidReason ?: "The run lost its evidence lineage.",
            level = IndicatorLevel.BLOCKED,
        )
    }
}

private fun temperatureSummary(run: AcThermalRunSnapshot): String =
    "Start ${formatAcTemperature(run.startTemperatureCelsius)}  •  " +
        "Current ${formatAcTemperature(run.currentTemperatureCelsius)}  •  " +
        "Maximum ${formatAcTemperature(run.maximumTemperatureCelsius)}  •  " +
        "Rise ${run.temperatureRiseCelsius?.let { String.format(Locale.US, "%+.1f °C", it) } ?: "—"}"

private fun formatAcTemperature(celsius: Double?): String = celsius?.let {
    String.format(Locale.US, "%.1f °C / %.1f °F", it, it * 9.0 / 5.0 + 32.0)
} ?: "—"

private fun formatAcDuration(milliseconds: Long?): String {
    if (milliseconds == null) return "—"
    if (milliseconds < 60_000L) return String.format(Locale.US, "%.1f s", milliseconds / 1_000.0)
    val totalSeconds = milliseconds / 1_000L
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
