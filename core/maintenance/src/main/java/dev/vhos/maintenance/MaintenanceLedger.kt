package dev.vhos.maintenance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

enum class DistanceUnit { MILES, KILOMETERS }

data class OdometerReading(
    val value: Long,
    val unit: DistanceUnit,
) {
    fun validate(): OdometerReading = apply {
        require(value in 0..20_000_000) { "Odometer reading is outside the supported range." }
    }
}

enum class ConfigurationResolutionStatus { UNKNOWN, PARTIAL, RESOLVED }
enum class ConfigurationAttributeState { KNOWN, UNKNOWN, NOT_APPLICABLE }
enum class ConfigurationAttributeSource {
    MANUAL, VIN_DECODE, OEM_DOCUMENT, DIAGNOSTIC, IMPORT, UNKNOWN,
}

data class VehicleConfigurationAttribute(
    val key: String,
    val state: ConfigurationAttributeState,
    val value: String? = null,
    val source: ConfigurationAttributeSource,
    val observedAt: String? = null,
) {
    fun validate(): VehicleConfigurationAttribute = apply {
        requireCanonicalKey(key, "configuration key")
        if (state == ConfigurationAttributeState.KNOWN) {
            require(!value.isNullOrBlank()) { "Known configuration attributes require a value." }
            requireText(requireNotNull(value), "configuration value", 1, 1000)
        } else {
            require(value == null) { "Unknown or inapplicable configuration attributes cannot carry a value." }
        }
        observedAt?.let { requireInstant(it, "configuration observed_at") }
    }
}

data class VehicleConfigurationSnapshot(
    val schemaVersion: String = "1.0.0",
    val resolutionStatus: ConfigurationResolutionStatus = ConfigurationResolutionStatus.UNKNOWN,
    val attributes: List<VehicleConfigurationAttribute> = emptyList(),
) {
    fun validate(): VehicleConfigurationSnapshot = apply {
        require(schemaVersion.matches(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$")))
        require(attributes.size <= 300)
        attributes.forEach { it.validate() }
        require(attributes.map { it.key }.distinct().size == attributes.size)
        when (resolutionStatus) {
            ConfigurationResolutionStatus.UNKNOWN -> require(attributes.none { it.state == ConfigurationAttributeState.KNOWN }) {
                "An UNKNOWN configuration cannot contain known attributes."
            }
            ConfigurationResolutionStatus.RESOLVED -> require(attributes.isNotEmpty() && attributes.none { it.state == ConfigurationAttributeState.UNKNOWN }) {
                "A RESOLVED configuration cannot contain unknown attributes."
            }
            ConfigurationResolutionStatus.PARTIAL -> Unit
        }
    }
}

enum class SevereUseStatus { YES, NO, UNKNOWN }
enum class SevereUseSource { OWNER, TECHNICIAN, IMPORT, SYSTEM, UNKNOWN }

data class SevereUseCondition(
    val conditionKey: String,
    val status: SevereUseStatus,
    val source: SevereUseSource,
    val observedAt: String? = null,
    val notes: String? = null,
) {
    fun validate(): SevereUseCondition = apply {
        requireCanonicalKey(conditionKey, "severe-use condition_key")
        observedAt?.let { requireInstant(it, "severe-use observed_at") }
        notes?.let { requireText(it, "severe-use notes", 1, 2000) }
    }
}

data class ActiveVehiclePackRef(
    val packId: String,
    val packVersion: String,
    val sourceManifestSha256: String,
    val activatedAt: String,
    val applicabilityStatus: String = "MATCHED",
    val matchedVehicleRevisionId: String,
) {
    fun validate(): ActiveVehiclePackRef = apply {
        requireCanonicalKey(packId, "pack_id")
        require(packVersion.matches(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$")))
        require(sourceManifestSha256.matches(Regex("^[0-9a-f]{64}$")))
        requireInstant(activatedAt, "pack activated_at")
        require(applicabilityStatus == "MATCHED")
        MaintenanceIds.requireVehicleRevision(matchedVehicleRevisionId, "matched_vehicle_revision_id")
    }
}

/** Generic vehicle identity. Vehicle-pack configuration remains a separate, versioned concern. */
data class VehicleAsset(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val vehicleId: String = MaintenanceIds.vehicle(),
    val revisionId: String = MaintenanceIds.vehicleRevision(),
    val supersedesRevisionId: String? = null,
    val createdAt: String = Instant.now().toString(),
    val displayName: String,
    val modelYear: Int,
    val make: String,
    val model: String,
    val trim: String? = null,
    val vin: String? = null,
    val licensePlate: String? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.MILES,
    val currentOdometer: OdometerReading? = null,
    val configuration: VehicleConfigurationSnapshot = VehicleConfigurationSnapshot(),
    val severeUseConditions: List<SevereUseCondition> = emptyList(),
    val activeVehiclePack: ActiveVehiclePackRef? = null,
) {
    fun validate(): VehicleAsset = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        MaintenanceIds.requireVehicle(vehicleId)
        MaintenanceIds.requireVehicleRevision(revisionId, "revision_id")
        supersedesRevisionId?.let {
            MaintenanceIds.requireVehicleRevision(it, "supersedes_revision_id")
            require(it != revisionId) { "A vehicle revision cannot supersede itself." }
        }
        requireInstant(createdAt, "created_at")
        requireText(displayName, "display_name", 1, 160)
        require(modelYear in 1886..3000) { "model_year is outside the supported range." }
        requireText(make, "make", 1, 120)
        requireText(model, "model", 1, 120)
        trim?.let { requireText(it, "trim", 1, 120) }
        vin?.let {
            require(it == it.uppercase()) { "VIN must be uppercase." }
            require(it.matches(Regex("^[A-HJ-NPR-Z0-9]{17}$"))) {
                "VIN must contain 17 valid ISO 3779 characters."
            }
        }
        licensePlate?.let { requireText(it, "license_plate", 1, 32) }
        currentOdometer?.validate()?.let {
            require(it.unit == distanceUnit) { "Vehicle and odometer distance units must agree." }
        }
        configuration.validate()
        require(severeUseConditions.size <= 100)
        severeUseConditions.forEach { it.validate() }
        require(severeUseConditions.map { it.conditionKey }.distinct().size == severeUseConditions.size)
        activeVehiclePack?.validate()?.also {
            require(it.matchedVehicleRevisionId == revisionId) {
                "An active Vehicle Pack must be matched to this exact vehicle revision."
            }
        }
    }

    companion object {
        const val CONTRACT = "vehicle.asset"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

enum class MaintenanceKnowledge { KNOWN, UNKNOWN }

data class MaintenanceSystemRef(
    val systemId: String? = null,
    val displayName: String? = null,
    val knowledge: MaintenanceKnowledge = MaintenanceKnowledge.KNOWN,
) {
    fun validate(): MaintenanceSystemRef = apply {
        when (knowledge) {
            MaintenanceKnowledge.KNOWN -> {
                requireCanonicalKey(requireNotNull(systemId), "system_id")
                requireText(requireNotNull(displayName), "system display_name", 1, 160)
            }
            MaintenanceKnowledge.UNKNOWN -> require(systemId == null && displayName == null) {
                "An unknown system cannot carry an invented identity or label."
            }
        }
    }

    companion object {
        fun unknown() = MaintenanceSystemRef(knowledge = MaintenanceKnowledge.UNKNOWN)
    }
}

data class MaintenanceComponentRef(
    val componentId: String? = null,
    val systemId: String? = null,
    val displayName: String? = null,
    val parentComponentId: String? = null,
    val componentKnowledge: MaintenanceKnowledge = MaintenanceKnowledge.KNOWN,
    val systemKnowledge: MaintenanceKnowledge = MaintenanceKnowledge.KNOWN,
) {
    fun validate(): MaintenanceComponentRef = apply {
        when (componentKnowledge) {
            MaintenanceKnowledge.KNOWN -> {
                MaintenanceIds.requireComponent(requireNotNull(componentId))
                requireText(requireNotNull(displayName), "component display_name", 1, 200)
            }
            MaintenanceKnowledge.UNKNOWN -> require(
                componentId == null && displayName == null && parentComponentId == null
            ) { "An unknown component cannot carry an invented identity, label, or parent." }
        }
        when (systemKnowledge) {
            MaintenanceKnowledge.KNOWN -> requireCanonicalKey(requireNotNull(systemId), "component system_id")
            MaintenanceKnowledge.UNKNOWN -> require(systemId == null) {
                "An unknown component system cannot carry an invented identity."
            }
        }
        parentComponentId?.let { parent ->
            MaintenanceIds.requireComponent(parent, "parent_component_id")
            require(parent != componentId) { "A component cannot be its own parent." }
        }
    }

    companion object {
        fun unknown() = MaintenanceComponentRef(
            componentKnowledge = MaintenanceKnowledge.UNKNOWN,
            systemKnowledge = MaintenanceKnowledge.UNKNOWN,
        )
    }
}

/**
 * Durable, vehicle-scoped component identity. Maintenance records keep [componentId] as the
 * stable foreign identity while this append-only registry carries the component's current label
 * and installation metadata. A rename therefore never creates a second physical component.
 */
enum class VehicleComponentState { ACTIVE, RETIRED }

data class VehicleComponentRevision(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val vehicleId: String,
    val componentId: String = MaintenanceIds.component(),
    val revisionId: String = MaintenanceIds.componentRevision(),
    val supersedesRevisionId: String? = null,
    val systemId: String,
    val systemDisplayName: String,
    val displayName: String,
    val parentComponentId: String? = null,
    val state: VehicleComponentState = VehicleComponentState.ACTIVE,
    val manufacturer: String? = null,
    val partNumber: String? = null,
    val serialNumber: String? = null,
    val installedAt: String? = null,
    val retiredAt: String? = null,
    val customFields: List<TypedCustomFieldValue> = emptyList(),
    val actor: MaintenanceActor,
    val createdAt: String = Instant.now().toString(),
    val amendmentReason: String? = null,
) {
    fun validate(): VehicleComponentRevision = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        MaintenanceIds.requireVehicle(vehicleId)
        MaintenanceIds.requireComponent(componentId)
        MaintenanceIds.requireComponentRevision(revisionId, "revision_id")
        supersedesRevisionId?.let {
            MaintenanceIds.requireComponentRevision(it, "supersedes_revision_id")
            require(it != revisionId) { "A component revision cannot supersede itself." }
        }
        requireCanonicalKey(systemId, "system_id")
        requireText(systemDisplayName, "system display_name", 1, 160)
        requireText(displayName, "component display_name", 1, 200)
        parentComponentId?.let {
            MaintenanceIds.requireComponent(it, "parent_component_id")
            require(it != componentId) { "A component cannot be its own parent." }
        }
        manufacturer?.let { requireText(it, "component manufacturer", 1, 200) }
        partNumber?.let { requireText(it, "component part_number", 1, 200) }
        serialNumber?.let { requireText(it, "component serial_number", 1, 200) }
        installedAt?.let { requireInstant(it, "installed_at") }
        retiredAt?.let { requireInstant(it, "retired_at") }
        require(customFields.size <= 500)
        customFields.forEach { it.validate() }
        require(customFields.map { it.fieldId }.distinct().size == customFields.size)
        actor.validate()
        requireInstant(createdAt, "created_at")
        if (supersedesRevisionId == null) {
            require(state == VehicleComponentState.ACTIVE) {
                "A component registry entry cannot begin retired."
            }
            require(amendmentReason == null) {
                "A new component registry entry cannot have an amendment reason."
            }
        } else {
            require(!amendmentReason.isNullOrBlank()) {
                "Component registry amendments require a reason."
            }
            requireText(requireNotNull(amendmentReason), "amendment_reason", 1, 2000)
        }
        if (state == VehicleComponentState.RETIRED) {
            require(supersedesRevisionId != null && retiredAt != null) {
                "Retiring a component requires a predecessor and retired_at."
            }
            if (installedAt != null) {
                require(!Instant.parse(requireNotNull(retiredAt)).isBefore(Instant.parse(installedAt))) {
                    "A component cannot be retired before it was installed."
                }
            }
        } else {
            require(retiredAt == null) { "An active component cannot have retired_at." }
        }
    }

    fun asMaintenanceRef(): MaintenanceComponentRef = MaintenanceComponentRef(
        componentId = componentId,
        systemId = systemId,
        displayName = displayName,
        parentComponentId = parentComponentId,
    ).validate()

    companion object {
        const val CONTRACT = "vehicle.component-registry-entry"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

enum class MaintenanceEventType {
    SERVICE,
    REPAIR,
    INSPECTION,
    REPLACEMENT,
    FLUID_SERVICE,
    INSTALLATION,
    REMOVAL,
    ADJUSTMENT,
    DIAGNOSTIC,
    OTHER,
}

enum class MaintenanceRecordState { ACTIVE, VOIDED }

enum class MaintenanceActorSource { OWNER, TECHNICIAN, IMPORT, SYSTEM }

data class MaintenanceActor(
    val source: MaintenanceActorSource,
    val actorId: String,
    val displayName: String,
) {
    fun validate(): MaintenanceActor = apply {
        MaintenanceIds.requireActor(actorId)
        requireText(displayName, "actor display_name", 1, 160)
    }
}

enum class MaintenanceOccurrencePrecision { DATE_ONLY, INSTANT }

data class MaintenanceProvider(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val invoiceNumber: String? = null,
) {
    fun validate(): MaintenanceProvider = apply {
        requireText(name, "provider name", 1, 240)
        phone?.let { requireText(it, "provider phone", 1, 80) }
        email?.let {
            requireText(it, "provider email", 3, 254)
            require('@' in it) { "Provider email is malformed." }
        }
        address?.let { requireText(it, "provider address", 1, 1000) }
        invoiceNumber?.let { requireText(it, "invoice_number", 1, 160) }
    }
}

data class Money(
    val currencyCode: String,
    val minorUnits: Long,
) {
    fun validate(): Money = apply {
        require(currencyCode.matches(Regex("^[A-Z]{3}$"))) { "Currency must be an ISO-4217 code." }
        Currency.getInstance(currencyCode)
        require(minorUnits in 0..9_000_000_000_000L) { "Money amount is outside the supported range." }
    }
}

enum class LineItemType { PART, FLUID, SUPPLY, LABOR, OTHER }

data class PartFluidLineItem(
    val lineItemId: String = MaintenanceIds.lineItem(),
    val type: LineItemType,
    val description: String,
    val manufacturer: String? = null,
    val partNumber: String? = null,
    val specification: String? = null,
    val quantity: String,
    val quantityUnit: String,
    val cost: Money? = null,
) {
    fun validate(): PartFluidLineItem = apply {
        MaintenanceIds.requireLineItem(lineItemId)
        requireText(description, "line item description", 1, 500)
        manufacturer?.let { requireText(it, "manufacturer", 1, 200) }
        partNumber?.let { requireText(it, "part_number", 1, 200) }
        specification?.let { requireText(it, "specification", 1, 500) }
        requirePositiveDecimal(quantity, "quantity")
        requireText(quantityUnit, "quantity_unit", 1, 80)
        cost?.validate()
    }
}

enum class MeasurementValueType { DECIMAL, TEXT, BOOLEAN }

data class MaintenanceMeasurement(
    val measurementId: String = MaintenanceIds.measurement(),
    val name: String,
    val valueType: MeasurementValueType,
    val value: String,
    val unit: String? = null,
    val method: String? = null,
    val conditionGrade: String? = null,
) {
    fun validate(): MaintenanceMeasurement = apply {
        MaintenanceIds.requireMeasurement(measurementId)
        requireText(name, "measurement name", 1, 200)
        when (valueType) {
            MeasurementValueType.DECIMAL -> {
                requireFiniteDecimal(value, "measurement value")
                require(!unit.isNullOrBlank()) { "Numeric measurements require a unit." }
            }
            MeasurementValueType.TEXT -> requireText(value, "measurement value", 1, 1000)
            MeasurementValueType.BOOLEAN -> require(value == "true" || value == "false") {
                "Boolean measurement values must be canonical true or false."
            }
        }
        unit?.let { requireText(it, "measurement unit", 1, 80) }
        method?.let { requireText(it, "measurement method", 1, 500) }
        conditionGrade?.let { requireText(it, "condition_grade", 1, 120) }
    }
}

data class WarrantyInfo(
    val provider: String,
    val startsOn: String,
    val expiresOn: String? = null,
    val distanceLimit: OdometerReading? = null,
    val terms: String? = null,
    val documentAttachmentIds: List<String> = emptyList(),
) {
    fun validate(): WarrantyInfo = apply {
        requireText(provider, "warranty provider", 1, 240)
        val start = requireDate(startsOn, "warranty starts_on")
        expiresOn?.let {
            require(!requireDate(it, "warranty expires_on").isBefore(start)) {
                "Warranty expiry precedes its start."
            }
        }
        distanceLimit?.validate()
        terms?.let { requireText(it, "warranty terms", 1, 10_000) }
        require(documentAttachmentIds.size <= 50)
        documentAttachmentIds.forEach {
            MaintenanceIds.requireAttachment(it, "warranty document_attachment_id")
        }
        require(documentAttachmentIds.distinct().size == documentAttachmentIds.size)
    }
}

enum class CustomValueType { TEXT, DECIMAL, INTEGER, BOOLEAN, DATE, INSTANT, CHOICE }

data class TypedCustomFieldValue(
    val fieldId: String,
    val label: String,
    val type: CustomValueType,
    val value: String,
    val unit: String? = null,
) {
    fun validate(): TypedCustomFieldValue = apply {
        requireCanonicalKey(fieldId, "custom field_id")
        requireText(label, "custom field label", 1, 200)
        when (type) {
            CustomValueType.TEXT, CustomValueType.CHOICE ->
                requireText(value, "custom field value", 1, 10_000)
            CustomValueType.DECIMAL -> requireFiniteDecimal(value, "custom decimal value")
            CustomValueType.INTEGER -> value.toLongOrNull()
                ?: throw IllegalArgumentException("Custom integer value is invalid.")
            CustomValueType.BOOLEAN -> require(value == "true" || value == "false") {
                "Custom Boolean values must be canonical true or false."
            }
            CustomValueType.DATE -> requireDate(value, "custom date value")
            CustomValueType.INSTANT -> requireInstant(value, "custom instant value")
        }
        if (unit != null) {
            require(type == CustomValueType.DECIMAL || type == CustomValueType.INTEGER) {
                "Only numeric custom fields may declare units."
            }
            requireText(unit, "custom field unit", 1, 80)
        }
    }
}

enum class AttachmentAvailability { AVAILABLE, MISSING }

data class AttachmentMetadata(
    val attachmentId: String = MaintenanceIds.attachment(),
    val displayName: String,
    val mediaType: String,
    val byteCount: Long,
    val sha256: String,
    val storageKey: String,
    val availability: AttachmentAvailability = AttachmentAvailability.AVAILABLE,
    val capturedAt: String? = null,
) {
    fun validate(): AttachmentMetadata = apply {
        MaintenanceIds.requireAttachment(attachmentId)
        requireText(displayName, "attachment display_name", 1, 255)
        require(mediaType.matches(Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$"))) {
            "Attachment media_type is malformed."
        }
        require(byteCount in 0..1_073_741_824L) { "Attachment byte_count is outside the supported range." }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "Attachment SHA-256 must be canonical lowercase hex." }
        requireText(storageKey, "attachment storage_key", 1, 500)
        capturedAt?.let { requireInstant(it, "attachment captured_at") }
    }
}

data class MaintenanceRecordRevision(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val recordId: String = MaintenanceIds.maintenanceRecord(),
    val revisionId: String = MaintenanceIds.maintenanceRevision(),
    val supersedesRevisionId: String? = null,
    val vehicleId: String,
    val vehicleAssetRevisionId: String,
    val state: MaintenanceRecordState = MaintenanceRecordState.ACTIVE,
    val eventType: MaintenanceEventType,
    val title: String,
    val occurredAt: String,
    val occurrencePrecision: MaintenanceOccurrencePrecision = MaintenanceOccurrencePrecision.INSTANT,
    val odometer: OdometerReading? = null,
    val engineHours: String? = null,
    val systems: List<MaintenanceSystemRef>,
    val components: List<MaintenanceComponentRef>,
    val provider: MaintenanceProvider? = null,
    val totalCost: Money? = null,
    val lineItems: List<PartFluidLineItem> = emptyList(),
    val measurements: List<MaintenanceMeasurement> = emptyList(),
    val warranty: WarrantyInfo? = null,
    val notes: String? = null,
    val customFields: List<TypedCustomFieldValue> = emptyList(),
    val attachments: List<AttachmentMetadata> = emptyList(),
    val completionClaims: List<MaintenanceCompletionClaim> = emptyList(),
    val actor: MaintenanceActor,
    val createdAt: String = Instant.now().toString(),
    val amendmentReason: String? = null,
) {
    fun validate(): MaintenanceRecordRevision = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        MaintenanceIds.requireMaintenanceRecord(recordId)
        MaintenanceIds.requireMaintenanceRevision(revisionId)
        MaintenanceIds.requireVehicle(vehicleId)
        MaintenanceIds.requireVehicleRevision(vehicleAssetRevisionId, "vehicle_asset_revision_id")
        supersedesRevisionId?.let {
            MaintenanceIds.requireMaintenanceRevision(it, "supersedes_revision_id")
            require(it != revisionId) { "A maintenance revision cannot supersede itself." }
        }
        when (occurrencePrecision) {
            MaintenanceOccurrencePrecision.DATE_ONLY -> requireDate(occurredAt, "occurred_at")
            MaintenanceOccurrencePrecision.INSTANT -> requireInstant(occurredAt, "occurred_at")
        }
        requireInstant(createdAt, "created_at")
        requireText(title, "title", 1, 300)
        odometer?.validate()
        engineHours?.let { requireNonNegativeDecimal(it, "engine_hours") }
        require(systems.isNotEmpty() && systems.size <= 32) { "At least one vehicle system is required." }
        systems.forEach { it.validate() }
        require(systems.mapNotNull { it.systemId }.distinct().size == systems.mapNotNull { it.systemId }.size)
        require(systems.count { it.knowledge == MaintenanceKnowledge.UNKNOWN } <= 1)
        require(components.isNotEmpty() && components.size <= 100) { "At least one component is required." }
        components.forEach { it.validate() }
        require(components.mapNotNull { it.componentId }.distinct().size == components.mapNotNull { it.componentId }.size)
        require(components.count { it.componentKnowledge == MaintenanceKnowledge.UNKNOWN } <= 1)
        val systemIds = systems.mapNotNull { it.systemId }.toSet()
        val hasUnknownSystem = systems.any { it.knowledge == MaintenanceKnowledge.UNKNOWN }
        require(components.all { component ->
            component.systemId?.let { it in systemIds } ?: hasUnknownSystem
        }) {
            "Every component must belong to one of the record's systems."
        }
        provider?.validate()
        totalCost?.validate()
        require(lineItems.size <= 200)
        lineItems.forEach { it.validate() }
        require(lineItems.map { it.lineItemId }.distinct().size == lineItems.size)
        require(measurements.size <= 500)
        measurements.forEach { it.validate() }
        require(measurements.map { it.measurementId }.distinct().size == measurements.size)
        warranty?.validate()
        notes?.let { requireText(it, "notes", 1, 50_000) }
        require(customFields.size <= 500)
        customFields.forEach { it.validate() }
        require(customFields.map { it.fieldId }.distinct().size == customFields.size)
        require(attachments.size <= 200)
        attachments.forEach { it.validate() }
        require(attachments.map { it.attachmentId }.distinct().size == attachments.size)
        require(completionClaims.size <= 100)
        val attachmentIds = attachments.map { it.attachmentId }.toSet()
        completionClaims.forEach { it.validate(attachmentIds) }
        require(completionClaims.map { it.baselineId }.distinct().size == completionClaims.size)
        warranty?.let { value ->
            require(value.documentAttachmentIds.all { it in attachmentIds }) {
                "Warranty document references must exist in this revision's attachments."
            }
        }
        actor.validate()
        if (supersedesRevisionId == null) {
            require(state == MaintenanceRecordState.ACTIVE) { "A new record cannot begin voided." }
            require(amendmentReason == null) { "A new record cannot have an amendment reason." }
        } else {
            require(!amendmentReason.isNullOrBlank()) { "Amendments require a reason." }
            requireText(requireNotNull(amendmentReason), "amendment_reason", 1, 2000)
        }
        if (state == MaintenanceRecordState.VOIDED) {
            require(supersedesRevisionId != null) { "Voiding requires a prior revision." }
        }
    }

    companion object {
        const val CONTRACT = "vehicle.maintenance-record"
        const val CONTRACT_VERSION = "1.0.0"
    }

    /** Conservative UTC boundary used only for ordering and elapsed-calendar projections. */
    fun occurrenceInstant(): Instant = when (occurrencePrecision) {
        MaintenanceOccurrencePrecision.INSTANT -> Instant.parse(occurredAt)
        MaintenanceOccurrencePrecision.DATE_ONLY -> LocalDate.parse(occurredAt)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
    }
}

enum class MaintenanceAuditAction { CREATED, AMENDED, VOIDED }

data class MaintenanceAuditEvent(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val auditEventId: String = MaintenanceIds.auditEvent(),
    val vehicleId: String,
    val recordId: String,
    val revisionId: String,
    val priorRevisionId: String?,
    val action: MaintenanceAuditAction,
    val actor: MaintenanceActor,
    val recordedAt: String,
    val reason: String?,
) {
    fun validate(): MaintenanceAuditEvent = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        MaintenanceIds.requireAuditEvent(auditEventId)
        MaintenanceIds.requireVehicle(vehicleId)
        MaintenanceIds.requireMaintenanceRecord(recordId)
        MaintenanceIds.requireMaintenanceRevision(revisionId)
        priorRevisionId?.let {
            MaintenanceIds.requireMaintenanceRevision(it, "prior_revision_id")
        }
        actor.validate()
        requireInstant(recordedAt, "recorded_at")
        when (action) {
            MaintenanceAuditAction.CREATED -> require(priorRevisionId == null && reason == null)
            MaintenanceAuditAction.AMENDED, MaintenanceAuditAction.VOIDED -> {
                require(priorRevisionId != null && !reason.isNullOrBlank())
                requireText(requireNotNull(reason), "audit reason", 1, 2000)
            }
        }
    }

    companion object {
        const val CONTRACT = "vehicle.maintenance-audit-event"
        const val CONTRACT_VERSION = "1.0.0"
    }
}

data class VoidMaintenanceRecordRequest(
    val vehicleId: String,
    val recordId: String,
    val actor: MaintenanceActor,
    val reason: String,
    val createdAt: String = Instant.now().toString(),
) {
    fun validate(): VoidMaintenanceRecordRequest = apply {
        MaintenanceIds.requireVehicle(vehicleId)
        MaintenanceIds.requireMaintenanceRecord(recordId)
        actor.validate()
        requireText(reason, "void reason", 1, 2000)
        requireInstant(createdAt, "created_at")
    }
}

data class MaintenanceSearchQuery(
    val vehicleId: String,
    val text: String? = null,
    val eventTypes: Set<MaintenanceEventType> = emptySet(),
    val systemId: String? = null,
    val componentId: String? = null,
    val occurredFrom: String? = null,
    val occurredThrough: String? = null,
    val includeVoided: Boolean = false,
    val limit: Int = 500,
) {
    fun validate(): MaintenanceSearchQuery = apply {
        MaintenanceIds.requireVehicle(vehicleId)
        text?.let { requireText(it, "search text", 1, 500) }
        systemId?.let { requireCanonicalKey(it, "system_id") }
        componentId?.let { MaintenanceIds.requireComponent(it) }
        val from = occurredFrom?.let { requireInstant(it, "occurred_from") }
        val through = occurredThrough?.let { requireInstant(it, "occurred_through") }
        if (from != null && through != null) require(!through.isBefore(from))
        require(limit in 1..1000)
    }
}

private fun requireInstant(value: String, field: String): Instant = try {
    Instant.parse(value)
} catch (error: RuntimeException) {
    throw IllegalArgumentException("$field must be an ISO-8601 instant.", error)
}

private fun requireDate(value: String, field: String): LocalDate = try {
    LocalDate.parse(value)
} catch (error: RuntimeException) {
    throw IllegalArgumentException("$field must be an ISO-8601 date.", error)
}

private fun requireText(value: String, field: String, minimum: Int, maximum: Int) {
    require(value == value.trim()) { "$field cannot contain leading or trailing whitespace." }
    require(value.length in minimum..maximum) { "$field length is outside the supported range." }
}

private fun requireCanonicalKey(value: String, field: String) {
    require(value.matches(Regex("^[a-z][a-z0-9._-]{0,119}$"))) { "$field is not a canonical key." }
}

private fun decimal(value: String, field: String): BigDecimal = try {
    BigDecimal(value).also { require(it.toString() == value) { "$field must use canonical decimal form." } }
} catch (error: NumberFormatException) {
    throw IllegalArgumentException("$field is not a decimal.", error)
}

private fun requireFiniteDecimal(value: String, field: String) {
    decimal(value, field)
}

private fun requirePositiveDecimal(value: String, field: String) {
    require(decimal(value, field) > BigDecimal.ZERO) { "$field must be greater than zero." }
}

private fun requireNonNegativeDecimal(value: String, field: String) {
    require(decimal(value, field) >= BigDecimal.ZERO) { "$field must not be negative." }
}
