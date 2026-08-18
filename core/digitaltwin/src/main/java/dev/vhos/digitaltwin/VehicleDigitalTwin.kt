package dev.vhos.digitaltwin

import java.time.Instant
import java.time.YearMonth
import java.util.UUID

enum class PermissionState { GRANTED, DENIED, NOT_APPLICABLE }

enum class UnknownSourceInstallState { ALLOWED, OWNER_APPROVAL_REQUIRED, UNAVAILABLE }

data class HeadUnitApplication(
    val packageId: String,
    val versionName: String,
    val versionCode: Long,
    val installerPackage: String?,
)

data class HeadUnitHardware(
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val cpuDescriptor: String?,
    val supportedAbis: List<String>,
    val logicalCpuCount: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val totalInternalStorageBytes: Long,
    val freeInternalStorageBytes: Long,
    val lowRamDevice: Boolean,
)

data class AndroidRuntime(
    val release: String,
    val apiLevel: Int,
    val securityPatch: String,
    val buildFingerprint: String,
)

data class HeadUnitDisplay(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    val widthDp: Int,
    val heightDp: Int,
)

data class HeadUnitCapabilities(
    val bleFeature: Boolean,
    val bluetoothScanPermission: PermissionState,
    val bluetoothConnectPermission: PermissionState,
    val notificationPermission: PermissionState,
    val unknownSourceInstall: UnknownSourceInstallState,
    val batteryOptimizationExempt: Boolean,
)

data class HeadUnitInventory(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val inventoryId: String = UUID.randomUUID().toString(),
    val capturedAt: String = Instant.now().toString(),
    val application: HeadUnitApplication,
    val hardware: HeadUnitHardware,
    val android: AndroidRuntime,
    val display: HeadUnitDisplay,
    val capabilities: HeadUnitCapabilities,
) {
    fun validate(): HeadUnitInventory = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        requireUuid(inventoryId, "inventory_id")
        requireInstant(capturedAt, "captured_at")
        require(application.packageId == "dev.vhos.headunit")
        require(application.versionName.isNotBlank() && application.versionCode > 0)
        require(
            listOf(
                hardware.manufacturer, hardware.model, hardware.device, hardware.product,
                hardware.board, hardware.hardware,
            ).all { it.isNotBlank() }
        )
        require(hardware.supportedAbis.isNotEmpty() && hardware.supportedAbis.all { it.isNotBlank() })
        require(hardware.supportedAbis.distinct().size == hardware.supportedAbis.size)
        require(hardware.logicalCpuCount > 0)
        require(hardware.totalRamBytes > 0 && hardware.availableRamBytes in 0..hardware.totalRamBytes)
        require(
            hardware.totalInternalStorageBytes > 0 &&
                hardware.freeInternalStorageBytes in 0..hardware.totalInternalStorageBytes
        )
        require(android.release.isNotBlank() && android.apiLevel >= 26)
        require(android.buildFingerprint.isNotBlank())
        require(
            display.widthPixels > 0 && display.heightPixels > 0 && display.densityDpi > 0 &&
                display.widthDp > 0 && display.heightDp > 0
        )
    }

    companion object {
        const val CONTRACT = "platform.head-unit-inventory"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

enum class TimingDrive(val displayName: String) {
    UNKNOWN("Unknown"),
    TIMING_CHAIN("Timing chain"),
    TIMING_BELT("Timing belt"),
}

enum class EngineConfiguration(
    val displayName: String,
    val timingDrive: TimingDrive,
) {
    UNKNOWN("Unknown engine", TimingDrive.UNKNOWN),
    V6_4_0L_1GR_FE("4.0L V6 (1GR-FE)", TimingDrive.TIMING_CHAIN),
    V8_4_7L_2UZ_FE("4.7L V8 (2UZ-FE)", TimingDrive.TIMING_BELT),
}

enum class Drivetrain(val displayName: String) {
    UNKNOWN("Unknown drivetrain"),
    TWO_WHEEL_DRIVE("2WD"),
    FOUR_WHEEL_DRIVE("4WD"),
}

enum class RearSuspension(val displayName: String) {
    UNKNOWN("Unknown rear suspension"),
    CONVENTIONAL("Conventional springs"),
    AIR_SUSPENSION("Rear air suspension"),
}

enum class TriState { UNKNOWN, YES, NO }

enum class ModificationState { UNKNOWN, STOCK, MODIFIED }

enum class MileageSource {
    UNKNOWN,
    MANUAL_ODOMETER,
    DIAGNOSTIC,
    IMPORTED_SERVICE_RECORD,
}

data class VehicleProfile(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val revisionId: String = UUID.randomUUID().toString(),
    val supersedesRevisionId: String? = null,
    val createdAt: String = Instant.now().toString(),
    val vehiclePackId: String = "toyota.4runner.2005",
    val vehiclePackVersion: String = "0.1.0",
    val modelYear: Int = 2005,
    val make: String = "Toyota",
    val model: String = "4Runner",
    val vin: String? = null,
    val engine: EngineConfiguration = EngineConfiguration.UNKNOWN,
    val timingDrive: TimingDrive = engine.timingDrive,
    val drivetrain: Drivetrain = Drivetrain.UNKNOWN,
    val rearSuspension: RearSuspension = RearSuspension.UNKNOWN,
    val trim: String? = null,
    val buildDate: String? = null,
    val tireConfiguration: String? = null,
    val severeUse: TriState = TriState.UNKNOWN,
    val modificationState: ModificationState = ModificationState.UNKNOWN,
    val modifications: List<String> = emptyList(),
    val currentMileage: Long? = null,
    val mileageObservedAt: String? = null,
    val mileageSource: MileageSource = MileageSource.UNKNOWN,
) {
    fun validate(): VehicleProfile = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        requireUuid(revisionId, "revision_id")
        supersedesRevisionId?.let { requireUuid(it, "supersedes_revision_id") }
        requireInstant(createdAt, "created_at")
        require(vehiclePackId == "toyota.4runner.2005" && vehiclePackVersion == "0.1.0")
        require(modelYear == 2005 && make == "Toyota" && model == "4Runner")
        vin?.let {
            require(it.matches(Regex("^[A-HJ-NPR-Z0-9]{17}$"))) { "VIN must be 17 valid characters." }
            require(it[9] == '5') { "VIN model-year position does not identify 2005." }
        }
        require(timingDrive == engine.timingDrive) {
            "Timing-drive applicability must be derived from the engine configuration."
        }
        trim?.let { require(it.isNotBlank() && it.length <= 120) }
        buildDate?.let {
            val month = YearMonth.parse(it)
            require(month in YearMonth.of(2004, 1)..YearMonth.of(2005, 12))
        }
        tireConfiguration?.let { require(it.isNotBlank() && it.length <= 160) }
        require(modifications.size <= 100)
        require(modifications.all { it.isNotBlank() && it.length <= 240 })
        require(modifications.distinct() == modifications)
        when (modificationState) {
            ModificationState.MODIFIED -> require(modifications.isNotEmpty())
            ModificationState.STOCK, ModificationState.UNKNOWN -> require(modifications.isEmpty())
        }
        if (currentMileage == null) {
            require(mileageObservedAt == null && mileageSource == MileageSource.UNKNOWN)
        } else {
            require(currentMileage in 0..2_000_000)
            require(mileageObservedAt != null && mileageSource != MileageSource.UNKNOWN)
            requireInstant(mileageObservedAt, "mileage_observed_at")
        }
    }

    fun scheduleReadinessIssues(): List<String> = buildList {
        if (vin == null) add("VIN")
        if (engine == EngineConfiguration.UNKNOWN) add("engine")
        if (drivetrain == Drivetrain.UNKNOWN) add("drivetrain")
        if (rearSuspension == RearSuspension.UNKNOWN) add("rear suspension")
        if (trim == null) add("trim")
        if (buildDate == null) add("build date")
        if (tireConfiguration == null) add("tire configuration")
        if (severeUse == TriState.UNKNOWN) add("severe-use selection")
        if (modificationState == ModificationState.UNKNOWN) add("modification selection")
        if (currentMileage == null) add("current mileage")
    }

    val scheduleReady: Boolean get() = scheduleReadinessIssues().isEmpty()

    companion object {
        const val CONTRACT = "vehicle.configuration-profile"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

enum class VehicleSystem(val displayName: String) {
    ENGINE("Engine"),
    ENGINE_COOLING("Engine cooling"),
    ENGINE_LUBRICATION("Engine lubrication"),
    FUEL_AND_INDUCTION("Fuel and induction"),
    IGNITION_EMISSIONS_EXHAUST("Ignition, emissions, and exhaust"),
    TRANSMISSION("Transmission"),
    TRANSFER_CASE("Transfer case"),
    FRONT_DIFFERENTIAL("Front differential"),
    REAR_DIFFERENTIAL("Rear differential"),
    DRIVESHAFT_AND_AXLES("Driveshaft and axles"),
    STARTING_CHARGING_BATTERY("Starting, charging, and battery"),
    HVAC_AND_AC("HVAC and A/C"),
    BRAKES("Brakes"),
    STEERING("Steering"),
    FRONT_SUSPENSION("Front suspension"),
    REAR_SUSPENSION("Rear suspension"),
    WHEELS_AND_TIRES("Wheels and tires"),
    BODY_FRAME_AND_CORROSION("Body, frame, and corrosion"),
    LIGHTING_AND_VISIBILITY("Lighting and visibility"),
    SAFETY_RESTRAINTS("Safety and restraints"),
    CABIN_CONTROLS_AND_ACCESSORIES("Cabin controls and accessories"),
    FLUIDS_HOSES_BELTS_AND_LEAKS("Fluids, hoses, belts, and leaks"),
}

enum class HealthState { UNKNOWN, OK, DUE_SOON, DUE, ATTENTION, CRITICAL }

enum class EvidenceBasis {
    DIRECT_MEASUREMENT,
    CALCULATED,
    SCHEDULE,
    INSPECTION,
    INFERRED,
    UNKNOWN,
}

data class HealthAssessment(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val assessmentId: String = UUID.randomUUID().toString(),
    val supersedesAssessmentId: String? = null,
    val profileRevisionId: String? = null,
    val systemId: VehicleSystem,
    val state: HealthState,
    val basis: EvidenceBasis,
    val recordedAt: String = Instant.now().toString(),
    val summary: String,
    val evidenceRefs: List<String> = emptyList(),
    val equationDefinitionId: String? = null,
    val equationVersion: String? = null,
    val confidence: Double? = null,
) {
    fun validate(): HealthAssessment = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        requireUuid(assessmentId, "assessment_id")
        supersedesAssessmentId?.let { requireUuid(it, "supersedes_assessment_id") }
        profileRevisionId?.let { requireUuid(it, "profile_revision_id") }
        requireInstant(recordedAt, "recorded_at")
        require(summary.isNotBlank() && summary.length <= 1000)
        require(evidenceRefs.distinct().size == evidenceRefs.size && evidenceRefs.size <= 100)
        confidence?.let { require(it in 0.0..1.0) }
        if (state == HealthState.UNKNOWN) {
            require(basis == EvidenceBasis.UNKNOWN)
            require(evidenceRefs.isEmpty())
            require(equationDefinitionId == null && equationVersion == null && confidence == null)
        } else {
            require(basis != EvidenceBasis.UNKNOWN)
            require(evidenceRefs.isNotEmpty()) {
                "A non-unknown health state requires evidence references."
            }
        }
        if (basis == EvidenceBasis.CALCULATED) {
            require(!equationDefinitionId.isNullOrBlank() && !equationVersion.isNullOrBlank())
            require(confidence != null)
        }
        if (basis == EvidenceBasis.INFERRED) require(confidence != null)
    }

    companion object {
        const val CONTRACT = "vehicle.health-assessment"
        const val CONTRACT_VERSION = "1.0.0"

        fun unknown(
            system: VehicleSystem,
            profileRevisionId: String?,
            supersedesAssessmentId: String? = null,
            recordedAt: String = Instant.now().toString(),
        ): HealthAssessment = HealthAssessment(
            supersedesAssessmentId = supersedesAssessmentId,
            profileRevisionId = profileRevisionId,
            systemId = system,
            state = HealthState.UNKNOWN,
            basis = EvidenceBasis.UNKNOWN,
            recordedAt = recordedAt,
            summary = "No qualifying evidence has established ${system.displayName.lowercase()} condition.",
        ).validate()
    }
}

data class DigitalTwinSnapshot(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val snapshotId: String = UUID.randomUUID().toString(),
    val exportedAt: String = Instant.now().toString(),
    val databaseVersion: Int,
    val headUnitInventory: HeadUnitInventory?,
    val vehicleProfile: VehicleProfile?,
    val healthAssessments: List<HealthAssessment>,
) {
    fun validate(): DigitalTwinSnapshot = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        requireUuid(snapshotId, "snapshot_id")
        requireInstant(exportedAt, "exported_at")
        require(databaseVersion >= 2)
        headUnitInventory?.validate()
        vehicleProfile?.validate()
        healthAssessments.forEach { it.validate() }
        require(healthAssessments.map { it.systemId }.toSet() == VehicleSystem.entries.toSet()) {
            "A whole-vehicle snapshot must contain exactly one current assessment per vehicle system."
        }
        require(healthAssessments.map { it.systemId }.distinct().size == healthAssessments.size)
    }

    companion object {
        const val CONTRACT = "vehicle.digital-twin.snapshot"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

data class HealthSummary(
    val totalSystems: Int,
    val unknownSystems: Int,
    val establishedSystems: Int,
    val byBasis: Map<EvidenceBasis, Int>,
) {
    companion object {
        fun from(assessments: List<HealthAssessment>): HealthSummary = HealthSummary(
            totalSystems = assessments.size,
            unknownSystems = assessments.count { it.state == HealthState.UNKNOWN },
            establishedSystems = assessments.count { it.state != HealthState.UNKNOWN },
            byBasis = EvidenceBasis.entries.associateWith { basis -> assessments.count { it.basis == basis } },
        )
    }
}

private fun requireUuid(value: String, field: String) {
    try {
        UUID.fromString(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("$field must be a UUID.", error)
    }
}

private fun requireInstant(value: String, field: String) {
    try {
        Instant.parse(value)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("$field must be an ISO-8601 instant.", error)
    }
}
