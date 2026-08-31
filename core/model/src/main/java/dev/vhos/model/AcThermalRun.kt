package dev.vhos.model

enum class AcTemperatureChannel(val wireValue: String, val displayName: String) {
    HIGH_LINE("high_line", "High / liquid line"),
    LOW_LINE("low_line", "Low / suction line"),
    AMBIENT("ambient", "Ambient"),
    CABIN_RETURN("cabin_return", "Cabin return"),
    CENTER_VENT("center_vent", "Center vent"),
}

enum class AcTemperatureQuality {
    GOOD,
    STALE,
    MISSING,
    OUT_OF_RANGE,
    DECODER_UNCERTAIN,
    TRANSPORT_GAP,
    SENSOR_UNVERIFIED,
    MANUALLY_ENTERED,
}

enum class AcCalibrationValidationStatus {
    EXPERIMENTAL,
    BENCH_VALIDATED,
    VEHICLE_VALIDATED,
    CALIBRATED,
}

data class AcTemperatureCalibration(
    val configured: Boolean,
    val calibrationId: String? = null,
    val revision: String? = null,
    val validationStatus: AcCalibrationValidationStatus? = null,
) {
    init {
        if (configured) {
            require(!calibrationId.isNullOrBlank()) { "Configured temperature calibration requires an identity." }
            require(!revision.isNullOrBlank()) { "Configured temperature calibration requires a revision." }
            requireNotNull(validationStatus) { "Configured temperature calibration requires a validation status." }
        } else {
            require(calibrationId == null && revision == null && validationStatus == null) {
                "Unconfigured temperature calibration cannot claim identity or validation."
            }
        }
    }

    val qualifiedForPerformance: Boolean
        get() = configured && validationStatus in setOf(
            AcCalibrationValidationStatus.BENCH_VALIDATED,
            AcCalibrationValidationStatus.VEHICLE_VALIDATED,
            AcCalibrationValidationStatus.CALIBRATED,
        )
}

/**
 * A schema-qualified temperature projection whose raw enclosing telemetry record has already been
 * durably persisted. Android receipt time is retained for evidence freshness, but elapsed thermal
 * time is calculated only from the sensor node's monotonic clock.
 */
data class AcTemperatureObservation(
    val deviceId: String,
    val captureId: String,
    val sampleCounter: ULong,
    val observedAtMonotonicMicroseconds: ULong,
    val receivedAtEpochMillis: Long,
    val channel: AcTemperatureChannel,
    val valueCelsius: Double?,
    val quality: AcTemperatureQuality,
    val calibration: AcTemperatureCalibration,
) {
    init {
        require(deviceId.isNotBlank() && captureId.isNotBlank()) {
            "Temperature evidence requires device and capture identities."
        }
        require(receivedAtEpochMillis > 0) { "Temperature receipt time is invalid." }
        require(valueCelsius == null || valueCelsius.isFinite()) {
            "Temperature must be finite when present."
        }
        if (quality == AcTemperatureQuality.GOOD) {
            require(valueCelsius != null) { "GOOD temperature evidence requires a value." }
        }
        if (quality == AcTemperatureQuality.MISSING) {
            require(valueCelsius == null) { "MISSING temperature evidence cannot carry a value." }
        }
    }

    val qualifiedForPerformance: Boolean
        get() = quality == AcTemperatureQuality.GOOD &&
            valueCelsius != null &&
            calibration.qualifiedForPerformance
}

enum class AcThermalRunPhase {
    IDLE,
    ARMED,
    RUNNING,
    COMPLETE,
    INVALID,
}

data class AcThermalRunSnapshot(
    val phase: AcThermalRunPhase = AcThermalRunPhase.IDLE,
    val targetChannel: AcTemperatureChannel = AcTemperatureChannel.CENTER_VENT,
    val requestedAtEpochMillis: Long? = null,
    val deviceId: String? = null,
    val captureId: String? = null,
    val acceptedSamples: Long = 0,
    val rejectedSamples: Long = 0,
    val startTemperatureCelsius: Double? = null,
    val currentTemperatureCelsius: Double? = null,
    val maximumTemperatureCelsius: Double? = null,
    val startMonotonicMicroseconds: ULong? = null,
    val currentMonotonicMicroseconds: ULong? = null,
    val maximumMonotonicMicroseconds: ULong? = null,
    val lastSampleCounter: ULong? = null,
    val elapsedMillis: Long? = null,
    val timeToMaximumMillis: Long? = null,
    val lastLimitation: String? = null,
    val invalidReason: String? = null,
) {
    val resultFinal: Boolean get() = phase == AcThermalRunPhase.COMPLETE
    val temperatureRiseCelsius: Double?
        get() = if (startTemperatureCelsius != null && maximumTemperatureCelsius != null) {
            maximumTemperatureCelsius - startTemperatureCelsius
        } else {
            null
        }

    companion object {
        const val CALCULATION_ID = "AC.TEMP.TIME_TO_MAX.v1"
    }
}

object AcThermalRunReducer {
    fun start(
        current: AcThermalRunSnapshot,
        requestedAtEpochMillis: Long,
        targetChannel: AcTemperatureChannel = AcTemperatureChannel.CENTER_VENT,
    ): AcThermalRunSnapshot {
        require(requestedAtEpochMillis > 0) { "Thermal-run request time is invalid." }
        if (current.phase == AcThermalRunPhase.ARMED ||
            current.phase == AcThermalRunPhase.RUNNING
        ) {
            return current
        }
        return AcThermalRunSnapshot(
            phase = AcThermalRunPhase.ARMED,
            targetChannel = targetChannel,
            requestedAtEpochMillis = requestedAtEpochMillis,
            lastLimitation = "Waiting for the first qualified ${targetChannel.displayName.lowercase()} sample.",
        )
    }

    fun accept(
        current: AcThermalRunSnapshot,
        observation: AcTemperatureObservation,
    ): AcThermalRunSnapshot {
        if (current.phase != AcThermalRunPhase.ARMED &&
            current.phase != AcThermalRunPhase.RUNNING
        ) {
            return current
        }
        if (observation.channel != current.targetChannel) return current
        if (!observation.qualifiedForPerformance) {
            return current.copy(
                rejectedSamples = current.rejectedSamples + 1,
                lastLimitation = buildString {
                    append("Rejected ${observation.quality.name} ${observation.channel.displayName.lowercase()} sample")
                    if (!observation.calibration.qualifiedForPerformance) {
                        append("; applicable validated calibration is absent")
                    }
                    append(".")
                },
            )
        }

        val value = requireNotNull(observation.valueCelsius)
        if (current.phase == AcThermalRunPhase.ARMED) {
            return current.copy(
                phase = AcThermalRunPhase.RUNNING,
                deviceId = observation.deviceId,
                captureId = observation.captureId,
                acceptedSamples = 1,
                startTemperatureCelsius = value,
                currentTemperatureCelsius = value,
                maximumTemperatureCelsius = value,
                startMonotonicMicroseconds = observation.observedAtMonotonicMicroseconds,
                currentMonotonicMicroseconds = observation.observedAtMonotonicMicroseconds,
                maximumMonotonicMicroseconds = observation.observedAtMonotonicMicroseconds,
                lastSampleCounter = observation.sampleCounter,
                elapsedMillis = 0,
                timeToMaximumMillis = 0,
                lastLimitation = null,
            )
        }

        if (observation.deviceId != current.deviceId || observation.captureId != current.captureId) {
            return invalidate(current, "Temperature source or capture changed during the run.")
        }
        val priorCounter = requireNotNull(current.lastSampleCounter)
        val priorMonotonic = requireNotNull(current.currentMonotonicMicroseconds)
        if (observation.sampleCounter == priorCounter &&
            observation.observedAtMonotonicMicroseconds == priorMonotonic
        ) {
            return current
        }
        if (observation.sampleCounter <= priorCounter ||
            observation.observedAtMonotonicMicroseconds <= priorMonotonic
        ) {
            return invalidate(current, "Temperature sequence or monotonic time regressed.")
        }

        val startMonotonic = requireNotNull(current.startMonotonicMicroseconds)
        val elapsedMillis = monotonicDeltaMillis(
            startMonotonic,
            observation.observedAtMonotonicMicroseconds,
        ) ?: return invalidate(current, "Temperature elapsed time exceeds the supported range.")
        val isNewMaximum = value > requireNotNull(current.maximumTemperatureCelsius)
        return current.copy(
            acceptedSamples = current.acceptedSamples + 1,
            currentTemperatureCelsius = value,
            maximumTemperatureCelsius = if (isNewMaximum) value else current.maximumTemperatureCelsius,
            currentMonotonicMicroseconds = observation.observedAtMonotonicMicroseconds,
            maximumMonotonicMicroseconds = if (isNewMaximum) {
                observation.observedAtMonotonicMicroseconds
            } else {
                current.maximumMonotonicMicroseconds
            },
            lastSampleCounter = observation.sampleCounter,
            elapsedMillis = elapsedMillis,
            timeToMaximumMillis = if (isNewMaximum) elapsedMillis else current.timeToMaximumMillis,
            lastLimitation = null,
        )
    }

    fun end(current: AcThermalRunSnapshot): AcThermalRunSnapshot = when (current.phase) {
        AcThermalRunPhase.ARMED -> invalidate(
            current,
            "Run ended before any qualified ${current.targetChannel.displayName.lowercase()} sample arrived.",
        )
        AcThermalRunPhase.RUNNING -> current.copy(phase = AcThermalRunPhase.COMPLETE)
        else -> current
    }

    fun reset(): AcThermalRunSnapshot = AcThermalRunSnapshot()

    private fun invalidate(current: AcThermalRunSnapshot, reason: String) = current.copy(
        phase = AcThermalRunPhase.INVALID,
        timeToMaximumMillis = null,
        invalidReason = reason,
        lastLimitation = reason,
    )

    private fun monotonicDeltaMillis(start: ULong, end: ULong): Long? {
        if (end < start) return null
        val millis = (end - start) / 1_000UL
        return millis.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
    }
}
