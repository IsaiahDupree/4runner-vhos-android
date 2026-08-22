package dev.vhos.discovery

import dev.vhos.model.ConnectionPhase
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.VehicleMotion

/**
 * Android-internal operational records for the Discovery laboratory. They are deliberately named
 * `Android...` because they are not the portable VHOS Discovery wire contracts. A finalized,
 * checksummed archive must be mapped into the platform-neutral CaptureSession/EventMarker/
 * VehicleCapabilitySnapshot contracts before cross-platform serialization.
 */
enum class AndroidDiscoveryTestCategory(val displayName: String) {
    ENGINE("Engine"),
    BRAKES("Brakes"),
    STEERING("Steering"),
    TRANSMISSION("Transmission"),
    HVAC("HVAC / A/C"),
    SUSPENSION("Suspension"),
    TIRES("Tires"),
    ELECTRICAL("Electrical"),
    FOUR_WHEEL_DRIVE("4WD"),
    CUSTOM("Custom"),
}

enum class AndroidDiscoveryExecutionAuthority {
    PARKED_PASSIVE,
    PASSENGER_SUPERVISED_DRIVE,
    SPECIALIST_SETUP,
}

enum class AndroidDiscoveryMarkerKind {
    STATE,
    MANUAL_MEASUREMENT,
    OBSERVATION,
}

data class AndroidDiscoveryMarkerDefinition(
    val eventType: String,
    val label: String,
    val kind: AndroidDiscoveryMarkerKind,
    val suggestedUnit: String? = null,
) {
    fun validate(): AndroidDiscoveryMarkerDefinition = apply {
        require(EVENT_TYPE.matches(eventType)) { "Discovery event type is not canonical." }
        require(label.isNotBlank()) { "Discovery event label is required." }
        require(suggestedUnit == null || suggestedUnit.isNotBlank()) {
            "A suggested measurement unit cannot be blank."
        }
        require(kind == AndroidDiscoveryMarkerKind.MANUAL_MEASUREMENT || suggestedUnit == null) {
            "Only measurement markers may declare a unit."
        }
    }

    private companion object {
        val EVENT_TYPE = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
    }
}

data class AndroidDiscoveryTestTemplate(
    val templateId: String,
    val version: String,
    val title: String,
    val category: AndroidDiscoveryTestCategory,
    val purpose: String,
    val executionAuthority: AndroidDiscoveryExecutionAuthority,
    val instructions: List<String>,
    val markers: List<AndroidDiscoveryMarkerDefinition>,
) {
    fun validate(): AndroidDiscoveryTestTemplate = apply {
        require(templateId.startsWith("vhos.discovery.")) { "Discovery template ID is not canonical." }
        require(VERSION.matches(version)) { "Discovery template version is invalid." }
        require(title.isNotBlank() && purpose.isNotBlank()) { "Discovery template text is incomplete." }
        require(instructions.isNotEmpty() && instructions.none(String::isBlank)) {
            "Discovery template instructions are required."
        }
        require(markers.isNotEmpty()) { "A discovery template requires at least one event marker." }
        markers.forEach(AndroidDiscoveryMarkerDefinition::validate)
        require(markers.map { it.eventType }.distinct().size == markers.size) {
            "Discovery template event types must be unique."
        }
    }

    private companion object {
        val VERSION = Regex("[1-9][0-9]*\\.[0-9]+\\.[0-9]+")
    }
}

object AndroidDiscoveryTestLibrary {
    const val CONTRACT_VERSION = "1.0.0"

    val templates: List<AndroidDiscoveryTestTemplate> = listOf(
        template(
            "ignition-cycle", "Ignition cycle", AndroidDiscoveryTestCategory.ELECTRICAL,
            "Separate accessory, ignition-on, crank, running, and shutdown transitions.",
            listOf("Keep the transmission in Park.", "Mark each key or start-button state after it becomes stable."),
            state("ignition.off", "Ignition OFF"), state("ignition.accessory", "Accessory"),
            state("ignition.on", "Ignition ON"), state("engine.cranking", "Cranking"),
            state("engine.running", "Engine running"),
        ),
        template(
            "cold-start", "Cold start", AndroidDiscoveryTestCategory.ENGINE,
            "Capture engine, electrical, and temperature behavior from a true cold start.",
            listOf("Use only when the engine is cold and the vehicle is parked.", "Mark before crank and after stable idle."),
            state("engine.before_crank", "Before crank"), state("engine.cranking", "Cranking"),
            state("engine.started", "Engine started"), observation("engine.idle_stable", "Idle stable"),
        ),
        template(
            "rpm-sweep", "RPM sweep", AndroidDiscoveryTestCategory.ENGINE,
            "Identify fields that track engine speed and engine-load transitions.",
            listOf("Keep the vehicle in Park with the parking brake applied.", "Use smooth, brief holds; stop if any unsafe condition appears."),
            state("throttle.released", "Throttle released"), state("throttle.applied", "Throttle applied"),
            observation("engine.rpm_hold", "RPM hold"),
        ),
        template(
            "accelerator-sweep", "Accelerator sweep", AndroidDiscoveryTestCategory.ENGINE,
            "Separate accelerator-pedal position from throttle and engine-speed fields.",
            listOf("Keep the vehicle parked.", "Apply and release the accelerator smoothly while marking endpoints."),
            state("accelerator.released", "Pedal released"), state("accelerator.applied", "Pedal applied"),
        ),
        template(
            "brake-pulse", "Brake pulse", AndroidDiscoveryTestCategory.BRAKES,
            "Find brake switch, pedal, pressure, and interlock candidates from repeated labeled pulses.",
            listOf("Keep the vehicle parked.", "Repeat release and press at least ten times without moving the vehicle."),
            state("brake.released", "Brake released"), state("brake.pressed", "Brake pressed"),
        ),
        template(
            "steering-sweep", "Steering sweep", AndroidDiscoveryTestCategory.STEERING,
            "Identify steering angle, direction, rate, and redundant-channel candidates.",
            listOf("Keep the vehicle parked where steering movement is safe.", "Mark center, left, center, and right positions."),
            state("steering.center", "Steering centered"), state("steering.left", "Steering left"),
            state("steering.right", "Steering right"),
        ),
        template(
            "wheel-rotation", "Wheel rotation", AndroidDiscoveryTestCategory.TIRES,
            "Distinguish individual wheel-speed and rotation-state candidates.",
            listOf("Use approved lift/support equipment and a qualified operator.", "Never work beneath an unsupported vehicle."),
            AndroidDiscoveryExecutionAuthority.SPECIALIST_SETUP,
            state("wheel.stationary", "Wheel stationary"), observation("wheel.rotating", "Wheel rotating"),
        ),
        template(
            "ac-cycle", "A/C ON / OFF", AndroidDiscoveryTestCategory.HVAC,
            "Correlate compressor/HVAC state with vehicle data and independent measurements.",
            listOf("Keep the vehicle parked with ventilation available.", "Mark each stable A/C state and every gauge measurement."),
            state("hvac.ac_off", "A/C OFF"), state("hvac.ac_on", "A/C ON"),
            measurement("measurement.ac_low_pressure", "Low-side pressure", "psi"),
            measurement("measurement.ac_high_pressure", "High-side pressure", "psi"),
            measurement("measurement.vent_temperature", "Vent temperature", "degF"),
            measurement("measurement.ambient_temperature", "Ambient temperature", "degF"),
        ),
        template(
            "blower-sweep", "Fan-speed sweep", AndroidDiscoveryTestCategory.HVAC,
            "Separate blower setting, fan feedback, and electrical-load candidates.",
            listOf("Keep the vehicle parked.", "Mark each blower step only after it stabilizes."),
            state("hvac.blower_off", "Blower OFF"), observation("hvac.blower_step", "Blower step changed"),
            state("hvac.blower_max", "Blower MAX"),
        ),
        template(
            "hvac-temperature-sweep", "HVAC temperature sweep", AndroidDiscoveryTestCategory.HVAC,
            "Separate temperature-command, blend-door, and measured-temperature candidates.",
            listOf("Keep the vehicle parked.", "Mark cold, midpoint, and hot commands after stabilization."),
            state("hvac.temperature_cold", "Temperature LO"), state("hvac.temperature_mid", "Temperature midpoint"),
            state("hvac.temperature_hot", "Temperature HI"),
        ),
        template(
            "four-wheel-drive-transition", "4WD transition", AndroidDiscoveryTestCategory.FOUR_WHEEL_DRIVE,
            "Identify transfer-case request, transition, and confirmed-state candidates.",
            listOf("Follow the Toyota owner procedure for the selected range.", "Do not force a transition; mark request and confirmed indicator separately."),
            state("four_wheel_drive.requested", "4WD requested"),
            observation("four_wheel_drive.indicator_confirmed", "4WD indicator confirmed"),
            state("four_wheel_drive.released", "4WD released"),
        ),
        template(
            "suspension-settle", "Suspension settle", AndroidDiscoveryTestCategory.SUSPENSION,
            "Correlate rear-height and compressor candidates with independent height measurements.",
            listOf("Keep the vehicle parked on level ground.", "Measure at repeatable body reference points."),
            measurement("measurement.ride_height_left", "Rear-left ride height", "mm"),
            measurement("measurement.ride_height_right", "Rear-right ride height", "mm"),
            observation("suspension.settled", "Suspension settled"),
        ),
        template(
            "tire-pressure-change", "Tire-pressure change", AndroidDiscoveryTestCategory.TIRES,
            "Correlate TPMS candidates with a calibrated independent pressure measurement.",
            listOf("Use a calibrated gauge and remain within Toyota tire-pressure limits.", "Record wheel position and pressure before and after any adjustment."),
            AndroidDiscoveryExecutionAuthority.SPECIALIST_SETUP,
            measurement("measurement.tire_pressure", "Tire pressure", "psi"),
            observation("tire.position_identified", "Wheel position identified"),
        ),
        template(
            "electrical-load", "Electrical load", AndroidDiscoveryTestCategory.ELECTRICAL,
            "Identify charging, battery-voltage, and load-response candidates.",
            listOf("Keep the vehicle parked.", "Mark each accessory load after electrical state stabilizes."),
            state("electrical.loads_off", "Loads OFF"), observation("electrical.load_added", "Load added"),
            measurement("measurement.system_voltage", "Independent system voltage", "V"),
        ),
        AndroidDiscoveryTestTemplate(
            templateId = "vhos.discovery.controlled-road-test",
            version = CONTRACT_VERSION,
            title = "Controlled road test",
            category = AndroidDiscoveryTestCategory.TRANSMISSION,
            purpose = "Separate speed, gear, shift, braking, and driveline candidates under motion.",
            executionAuthority = AndroidDiscoveryExecutionAuthority.PASSENGER_SUPERVISED_DRIVE,
            instructions = listOf(
                "A passenger operates Discovery; the driver does not interact with the screen.",
                "Use a legal controlled route and stop the test if conditions change.",
            ),
            markers = listOf(
                state("vehicle.stopped", "Vehicle stopped"), observation("vehicle.accelerating", "Accelerating"),
                observation("transmission.shift_observed", "Shift observed"),
                observation("vehicle.coasting", "Coasting"), state("brake.pressed", "Brake pressed"),
            ),
        ).validate(),
    ).also { library ->
        require(library.map { it.templateId }.distinct().size == library.size) {
            "Discovery test template identities must be unique."
        }
    }

    fun requireTemplate(templateId: String, version: String): AndroidDiscoveryTestTemplate =
        requireNotNull(templates.singleOrNull { it.templateId == templateId && it.version == version }) {
            "Discovery test template $templateId@$version is not installed."
        }

    private fun template(
        slug: String,
        title: String,
        category: AndroidDiscoveryTestCategory,
        purpose: String,
        instructions: List<String>,
        vararg markers: AndroidDiscoveryMarkerDefinition,
    ) = template(slug, title, category, purpose, instructions, AndroidDiscoveryExecutionAuthority.PARKED_PASSIVE, *markers)

    private fun template(
        slug: String,
        title: String,
        category: AndroidDiscoveryTestCategory,
        purpose: String,
        instructions: List<String>,
        authority: AndroidDiscoveryExecutionAuthority,
        vararg markers: AndroidDiscoveryMarkerDefinition,
    ) = AndroidDiscoveryTestTemplate(
        templateId = "vhos.discovery.$slug",
        version = CONTRACT_VERSION,
        title = title,
        category = category,
        purpose = purpose,
        executionAuthority = authority,
        instructions = instructions,
        markers = markers.toList(),
    ).validate()

    private fun state(id: String, label: String) =
        AndroidDiscoveryMarkerDefinition("event.$id", label, AndroidDiscoveryMarkerKind.STATE)

    private fun observation(id: String, label: String) =
        AndroidDiscoveryMarkerDefinition("event.$id", label, AndroidDiscoveryMarkerKind.OBSERVATION)

    private fun measurement(id: String, label: String, unit: String) =
        AndroidDiscoveryMarkerDefinition(id, label, AndroidDiscoveryMarkerKind.MANUAL_MEASUREMENT, unit)
}

data class AndroidDiscoveryEvidenceAnchor(
    val sourceId: String,
    val canSessionId: UInt,
    val sourceSequence: ULong,
    val gatewayMonotonicMicroseconds: ULong,
) {
    fun validate(): AndroidDiscoveryEvidenceAnchor = apply {
        require(sourceId.isNotBlank()) { "Discovery evidence anchor requires a source identity." }
    }
}

enum class AndroidCaptureDraftState { ACTIVE, COMPLETED, ABORTED }

enum class AndroidDiscoverySafetyEvidence {
    VALIDATED_GATEWAY_HEALTH_PARKED,
}

data class AndroidDiscoveryEngineeringGate(
    val allowed: Boolean,
    val detail: String,
    val authorization: AndroidDiscoverySafetyAuthorization? = null,
)

/** Exact validated gateway-health frame that authorized a parked-only engineering mutation. */
data class AndroidDiscoverySafetyAuthorization(
    val sourceId: String,
    val healthFrameSequence: ULong,
    val healthGatewayMonotonicMicroseconds: ULong,
    val receivedAtEpochMillis: Long,
) {
    fun validate(): AndroidDiscoverySafetyAuthorization = apply {
        require(sourceId.isNotBlank()) { "PARKED authorization requires a validated source identity." }
        require(receivedAtEpochMillis >= 0) { "PARKED authorization receipt time is invalid." }
    }
}

/**
 * Single Android authority gate for Discovery mutations. Only a recent validated gateway-health
 * PARKED report on an actively streaming contract is sufficient. A zero speed, user assertion,
 * retained observation, or stale PARKED state is deliberately insufficient.
 */
object AndroidDiscoveryEngineeringSafetyGate {
    const val HEALTH_FRESHNESS_MILLIS = 2_000L

    fun evaluate(
        device: DeviceSnapshot,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AndroidDiscoveryEngineeringGate {
        val motionAge = device.vehicleMotionObservedAtEpochMs?.let { nowEpochMillis - it }
        val transportAge = device.lastFrameAtEpochMs?.let { nowEpochMillis - it }
        val sourceId = device.sourceId
        val motionFrameSequence = device.vehicleMotionFrameSequence
        val motionGatewayMonotonic = device.vehicleMotionGatewayMonotonicMicroseconds
        val motionObservedAt = device.vehicleMotionObservedAtEpochMs
        val authorization = if (
            sourceId != null && motionFrameSequence != null && motionGatewayMonotonic != null &&
            motionObservedAt != null
        ) {
            AndroidDiscoverySafetyAuthorization(
                sourceId = sourceId,
                healthFrameSequence = motionFrameSequence,
                healthGatewayMonotonicMicroseconds = motionGatewayMonotonic,
                receivedAtEpochMillis = motionObservedAt,
            ).validate()
        } else {
            null
        }
        val allowed = device.phase == ConnectionPhase.STREAMING &&
            device.vehicleMotion == VehicleMotion.PARKED &&
            motionAge != null && motionAge in 0..HEALTH_FRESHNESS_MILLIS &&
            transportAge != null && transportAge in 0..HEALTH_FRESHNESS_MILLIS &&
            authorization != null
        return AndroidDiscoveryEngineeringGate(
            allowed = allowed,
            detail = if (allowed) {
                "Fresh validated gateway health deterministically reports PARKED."
            } else {
                "No fresh validated gateway-health evidence proves PARKED. " +
                    "Speed=0 and owner assertion do not satisfy this gate."
            },
            authorization = authorization.takeIf { allowed },
        )
    }
}

/** Current live-bus evidence must come from a recent validated RAW_CAN receipt, not a counter. */
object AndroidDiscoveryLiveEvidence {
    fun isVehicleBusCurrent(
        device: DeviceSnapshot,
        expectedSourceId: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
        freshnessMillis: Long = AndroidDiscoveryEngineeringSafetyGate.HEALTH_FRESHNESS_MILLIS,
    ): Boolean {
        require(freshnessMillis >= 0)
        val age = device.lastVehicleFrameAtEpochMs?.let { nowEpochMillis - it }
        return expectedSourceId != null && device.sourceId == expectedSourceId &&
            device.phase == ConnectionPhase.STREAMING && age != null && age in 0..freshnessMillis
    }
}

enum class AndroidCaptureFinalizationAuthority {
    PARKED_VERIFIED_COMPLETION,
    OWNER_SAFETY_ABORT,
    INTERRUPTED_BY_REBOOT,
}

data class AndroidDiscoveryCaptureDraft(
    val sessionId: String,
    val vehicleScopeId: String,
    val vehicleProfileRevisionId: String,
    val sourceId: String,
    val testTemplateId: String,
    val testTemplateVersion: String,
    val testTemplateSnapshot: AndroidDiscoveryTestTemplate,
    val state: AndroidCaptureDraftState,
    val startedAt: String,
    val startedElapsedRealtimeNanos: Long,
    val startedBootId: String,
    val endedAt: String?,
    val endedElapsedRealtimeNanos: Long?,
    val endedBootId: String?,
    val startAnchor: AndroidDiscoveryEvidenceAnchor?,
    val endAnchor: AndroidDiscoveryEvidenceAnchor?,
    val startLogicalFrameCount: Long,
    val startCanObservationCount: Long,
    val endLogicalFrameCount: Long?,
    val endCanObservationCount: Long?,
    val safetyEvidence: AndroidDiscoverySafetyEvidence,
    val safetyAuthorization: AndroidDiscoverySafetyAuthorization,
    val finalizationAuthority: AndroidCaptureFinalizationAuthority?,
    val finalizationSafetyAuthorization: AndroidDiscoverySafetyAuthorization?,
) {
    fun validate(): AndroidDiscoveryCaptureDraft = apply {
        require(
            sessionId.isNotBlank() && vehicleScopeId.isNotBlank() && sourceId.isNotBlank() &&
                startedAt.isNotBlank() && startedElapsedRealtimeNanos >= 0 && startedBootId.isNotBlank()
        ) {
            "AndroidDiscoveryCaptureDraft start identity and clocks are required."
        }
        require(vehicleProfileRevisionId.isNotBlank())
        testTemplateSnapshot.validate()
        require(
            testTemplateSnapshot.templateId == testTemplateId &&
                testTemplateSnapshot.version == testTemplateVersion
        ) { "Persisted test-template identity does not match its immutable snapshot." }
        safetyAuthorization.validate()
        require(safetyAuthorization.sourceId == sourceId) {
            "PARKED authorization source does not match the capture source."
        }
        require(startLogicalFrameCount >= 0 && startCanObservationCount >= 0) {
            "AndroidDiscoveryCaptureDraft evidence cursors cannot be negative."
        }
        if (state == AndroidCaptureDraftState.ACTIVE) {
            require(endedAt == null && endedElapsedRealtimeNanos == null && endedBootId == null &&
                endAnchor == null && endLogicalFrameCount == null && endCanObservationCount == null &&
                finalizationAuthority == null && finalizationSafetyAuthorization == null
            ) { "An active AndroidDiscoveryCaptureDraft cannot contain final evidence cursors." }
        } else {
            require(!endedAt.isNullOrBlank() && endedElapsedRealtimeNanos != null &&
                !endedBootId.isNullOrBlank() && endLogicalFrameCount != null &&
                endCanObservationCount != null && finalizationAuthority != null
            ) { "A final AndroidDiscoveryCaptureDraft requires final clocks and evidence cursors." }
            if (endedBootId == startedBootId) {
                require(endedElapsedRealtimeNanos >= startedElapsedRealtimeNanos) {
                    "AndroidDiscoveryCaptureDraft monotonic clocks moved backwards within one boot."
                }
            } else {
                require(
                    state == AndroidCaptureDraftState.ABORTED &&
                        finalizationAuthority == AndroidCaptureFinalizationAuthority.INTERRUPTED_BY_REBOOT
                ) { "Cross-boot capture recovery must be an explicit interrupted abort." }
            }
            require(endLogicalFrameCount >= startLogicalFrameCount &&
                endCanObservationCount >= startCanObservationCount
            ) { "AndroidDiscoveryCaptureDraft evidence counts moved backwards." }
            if (state == AndroidCaptureDraftState.COMPLETED) {
                require(finalizationAuthority == AndroidCaptureFinalizationAuthority.PARKED_VERIFIED_COMPLETION) {
                    "A completed capture requires current PARKED authority."
                }
                require(finalizationSafetyAuthorization?.sourceId == sourceId) {
                    "A completed capture requires exact final PARKED-frame lineage."
                }
            } else {
                require(finalizationSafetyAuthorization == null) {
                    "A safety abort cannot claim PARKED finalization authority."
                }
            }
        }
    }
}

data class AndroidDiscoveryMarkerRecord(
    val markerId: String,
    val captureSessionId: String,
    val eventType: String,
    val label: String,
    val kind: AndroidDiscoveryMarkerKind,
    val value: String?,
    val unit: String?,
    val observedAt: String,
    val elapsedRealtimeNanos: Long,
    val evidenceAnchor: AndroidDiscoveryEvidenceAnchor?,
    val observer: String,
    val note: String?,
    val safetyAuthorization: AndroidDiscoverySafetyAuthorization,
) {
    fun validate(): AndroidDiscoveryMarkerRecord = apply {
        require(markerId.isNotBlank() && captureSessionId.isNotBlank()) {
            "AndroidDiscoveryMarkerRecord identity is required."
        }
        AndroidDiscoveryMarkerDefinition(eventType, label, kind, unit).validate()
        require(observedAt.isNotBlank() && elapsedRealtimeNanos >= 0 && observer.isNotBlank()) {
            "AndroidDiscoveryMarkerRecord clocks and observer are required."
        }
        require(kind != AndroidDiscoveryMarkerKind.MANUAL_MEASUREMENT || !value.isNullOrBlank()) {
            "A manual measurement AndroidDiscoveryMarkerRecord requires a value."
        }
        require(value == null || value.isNotBlank()) { "AndroidDiscoveryMarkerRecord value cannot be blank." }
        require(note == null || note.isNotBlank()) { "AndroidDiscoveryMarkerRecord note cannot be blank." }
        safetyAuthorization.validate()
    }
}

data class AndroidVehicleCapabilityObservation(
    val snapshotId: String,
    val capturedAt: String,
    val vehicleScopeId: String,
    val vehicleProfileRevisionId: String,
    val sourceId: String,
    val gatewayFirmwareVersion: String?,
    val gatewayContractActive: Boolean,
    val listenOnlyProven: Boolean?,
    val canCommunicationDetected: Boolean?,
    val canBitratesBps: List<Long>,
    val retainedCanObservations: Long,
    val uniqueCanIdentifiers: Int?,
    val obdEcuCount: Int,
    val obdEnumerationComplete: Boolean?,
    val supportedObdPidCount: Int,
    val availableStandardSignalIds: List<String>,
    val safetyAuthorization: AndroidDiscoverySafetyAuthorization,
    val authority: String = AUTHORITY,
) {
    fun validate(): AndroidVehicleCapabilityObservation = apply {
        require(snapshotId.isNotBlank() && capturedAt.isNotBlank()) {
            "AndroidVehicleCapabilityObservation identity and capture time are required."
        }
        require(vehicleScopeId.isNotBlank() && vehicleProfileRevisionId.isNotBlank() && sourceId.isNotBlank()) {
            "Vehicle capability observations require an explicit vehicle, profile, and source scope."
        }
        require(canBitratesBps.all { it > 0 } && canBitratesBps.distinct().size == canBitratesBps.size)
        require(retainedCanObservations >= 0 && (uniqueCanIdentifiers == null || uniqueCanIdentifiers >= 0))
        require(obdEcuCount >= 0 && supportedObdPidCount >= 0)
        require(availableStandardSignalIds.none(String::isBlank) &&
            availableStandardSignalIds.distinct().size == availableStandardSignalIds.size
        )
        require(authority == AUTHORITY) { "Vehicle capability authority was widened." }
        safetyAuthorization.validate()
        require(safetyAuthorization.sourceId == sourceId) {
            "Capability PARKED authorization source does not match the observed gateway."
        }
    }

    companion object {
        const val AUTHORITY =
            "Observed transport and read-only availability evidence only; absence is not proof of unsupported vehicle capability."
    }
}

data class AndroidSignalPromotionGate(
    val signalDefinition: Boolean,
    val sourceDefined: Boolean,
    val decoderDefined: Boolean,
    val unitsAndTypeDefined: Boolean,
    val plausibleRangeDefined: Boolean,
    val freshnessRuleDefined: Boolean,
    val targetVehicleCapture: Boolean,
    val independentCorroboration: Boolean,
    val goldenReplay: Boolean,
) {
    val ready: Boolean get() = signalDefinition && sourceDefined && decoderDefined &&
        unitsAndTypeDefined && plausibleRangeDefined && freshnessRuleDefined && targetVehicleCapture &&
        independentCorroboration && goldenReplay
}

data class AndroidCandidateResearchItem(
    val candidateId: String,
    val sourceDescription: String,
    val proposedCanonicalSignalId: String?,
    val evidenceStatus: String,
    val retainedRecords: Int,
    val captureSessions: Int,
    val researchPriority: Int,
    val confidence: Double?,
    val authority: String,
    val nextValidation: String,
    val promotionChecklist: AndroidSignalPromotionGate,
) {
    fun validate(): AndroidCandidateResearchItem = apply {
        require(candidateId.isNotBlank() && sourceDescription.isNotBlank() && evidenceStatus.isNotBlank())
        require(retainedRecords >= 0 && captureSessions >= 0 && researchPriority in 0..100)
        require(confidence == null || confidence in 0.0..1.0)
        require(authority == AUTHORITY && nextValidation.isNotBlank())
        require(!promotionChecklist.ready) {
            "Discovery-only AndroidCandidateResearchItem cannot become promotion-ready without a validated registry contract."
        }
    }

    companion object {
        const val AUTHORITY =
            "Engineering candidate only; priority is not confidence and owner display is prohibited."
    }
}

object AndroidCandidateResearchAdapter {
    fun from(
        evaluation: SignalHypothesisEvaluationReport,
        research: SignalResearchBrief,
    ): List<AndroidCandidateResearchItem> {
        require(evaluation.packId == research.packId &&
            evaluation.packVersion == research.packVersion &&
            evaluation.packSha256 == research.packSha256
        ) { "Candidate research lineage does not match its evaluated hypothesis pack." }
        val evaluationById = evaluation.evaluations.associateBy { it.hypothesisId }
        return research.missions.map { mission ->
            val candidate = requireNotNull(evaluationById[mission.hypothesisId]) {
                "Candidate research mission has no evaluation."
            }
            AndroidCandidateResearchItem(
                candidateId = candidate.hypothesisId,
                sourceDescription = "${candidate.identifierHex} • ${candidate.targetEvidenceStatus}",
                proposedCanonicalSignalId = candidate.candidateSemantic,
                evidenceStatus = candidate.targetEvidenceStatus,
                retainedRecords = candidate.records,
                captureSessions = candidate.sessions,
                researchPriority = mission.researchPriority,
                confidence = null,
                authority = AndroidCandidateResearchItem.AUTHORITY,
                nextValidation = mission.nextValidation,
                promotionChecklist = AndroidSignalPromotionGate(
                    // A research hypothesis and transform are not accepted definitions or decoders.
                    signalDefinition = false,
                    sourceDefined = candidate.records > 0,
                    decoderDefined = false,
                    unitsAndTypeDefined = false,
                    plausibleRangeDefined = false,
                    freshnessRuleDefined = false,
                    targetVehicleCapture = candidate.records > 0,
                    independentCorroboration = false,
                    goldenReplay = false,
                ),
            ).validate()
        }
    }
}
