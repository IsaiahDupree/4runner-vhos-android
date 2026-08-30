package dev.vhos.maintenance

import java.time.Instant

/**
 * Complete portable source ledger for one vehicle.
 *
 * The archive codec protects the bytes and attachment bodies; this object protects the domain
 * topology. It contains every immutable revision, rather than only the current projections, so a
 * restore can reproduce amendments, voids, component lineage, rule lineage, and audit receipts.
 */
data class MaintenanceLedgerArchivePayload(
    val contract: String = CONTRACT,
    val contractVersion: String = CONTRACT_VERSION,
    val vehicleId: String,
    val exportedAt: String,
    val vehicleAssetRevisions: List<VehicleAsset>,
    val componentRevisions: List<VehicleComponentRevision>,
    val requirementRevisions: List<MaintenanceRequirementRevision>,
    val recordRevisions: List<MaintenanceRecordRevision>,
    val auditEvents: List<MaintenanceAuditEvent>,
) {
    fun validate(): MaintenanceLedgerArchivePayload = apply {
        require(contract == CONTRACT && contractVersion == CONTRACT_VERSION)
        MaintenanceIds.requireVehicle(vehicleId)
        try {
            Instant.parse(exportedAt)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("exported_at must be an ISO-8601 instant.", error)
        }
        require(vehicleAssetRevisions.isNotEmpty()) {
            "A maintenance archive must contain its vehicle-asset history."
        }
        require(vehicleAssetRevisions.size <= 100_000)
        require(componentRevisions.size <= 500_000)
        require(requirementRevisions.size <= 500_000)
        require(recordRevisions.size <= 1_000_000)
        require(auditEvents.size <= 1_000_000)

        vehicleAssetRevisions.forEach { revision ->
            revision.validate()
            require(revision.vehicleId == vehicleId) { "Archive contains another vehicle's asset revision." }
        }
        componentRevisions.forEach { revision ->
            revision.validate()
            require(revision.vehicleId == vehicleId) { "Archive contains another vehicle's component revision." }
        }
        requirementRevisions.forEach { revision ->
            revision.validate()
            require(revision.vehicleId == vehicleId) { "Archive contains another vehicle's requirement revision." }
        }
        recordRevisions.forEach { revision ->
            revision.validate()
            require(revision.vehicleId == vehicleId) { "Archive contains another vehicle's maintenance revision." }
        }
        auditEvents.forEach { event ->
            event.validate()
            require(event.vehicleId == vehicleId) { "Archive contains another vehicle's audit event." }
        }

        requireUnique(vehicleAssetRevisions.map { it.revisionId }, "vehicle revision")
        requireUnique(componentRevisions.map { it.revisionId }, "component revision")
        requireUnique(requirementRevisions.map { it.requirementId }, "requirement revision")
        requireUnique(recordRevisions.map { it.revisionId }, "maintenance revision")
        requireUnique(auditEvents.map { it.auditEventId }, "maintenance audit event")

        requireLinearHistories(
            vehicleAssetRevisions,
            identity = VehicleAsset::revisionId,
            predecessor = VehicleAsset::supersedesRevisionId,
            group = { it.vehicleId },
            createdAt = VehicleAsset::createdAt,
            label = "vehicle",
        )
        requireLinearHistories(
            componentRevisions,
            identity = VehicleComponentRevision::revisionId,
            predecessor = VehicleComponentRevision::supersedesRevisionId,
            group = { it.componentId },
            createdAt = VehicleComponentRevision::createdAt,
            label = "component",
        )
        requireLinearHistories(
            requirementRevisions,
            identity = MaintenanceRequirementRevision::requirementId,
            predecessor = MaintenanceRequirementRevision::supersedesRequirementId,
            group = { it.task.taskId },
            createdAt = MaintenanceRequirementRevision::createdAt,
            label = "requirement",
        )
        requireLinearHistories(
            recordRevisions,
            identity = MaintenanceRecordRevision::revisionId,
            predecessor = MaintenanceRecordRevision::supersedesRevisionId,
            group = { it.recordId },
            createdAt = MaintenanceRecordRevision::createdAt,
            label = "maintenance record",
        )

        val vehicleRevisionIds = vehicleAssetRevisions.map { it.revisionId }.toSet()
        val componentIds = componentRevisions.map { it.componentId }.toSet()
        require(componentRevisions.all { it.parentComponentId == null || it.parentComponentId in componentIds }) {
            "A component parent is absent from the archive."
        }
        require(requirementRevisions.all { it.vehicleAssetRevisionId in vehicleRevisionIds }) {
            "A maintenance requirement references a missing vehicle revision."
        }
        require(recordRevisions.all { it.vehicleAssetRevisionId in vehicleRevisionIds }) {
            "A maintenance record references a missing vehicle revision."
        }
        require(recordRevisions.flatMap { it.components }.all { component ->
            component.componentId == null || component.componentId in componentIds
        }) {
            "A maintenance record references a missing registered component."
        }
        val requirementsById = requirementRevisions.associateBy { it.requirementId }
        require(recordRevisions.flatMap { it.completionClaims }.all { claim ->
            val requirement = requirementsById[claim.requirementId] ?: return@all false
            if (requirement.task.taskId != claim.taskId) return@all false
            val rule = requirement.verifiedRule
            if (claim.ruleId == null) {
                rule == null
            } else {
                rule != null && claim.ruleId == rule.ruleId && claim.packId == rule.packId &&
                    claim.packVersion == rule.packVersion
            }
        }) {
            "A maintenance completion claim has missing or mismatched requirement lineage."
        }
        val baselineIds = recordRevisions.flatMap { revision ->
            revision.completionClaims.map { it.baselineId }
        }
        require(baselineIds.distinct().size == baselineIds.size) {
            "Completion baseline identities must be globally unique in an archive."
        }
        val recordRevisionIds = recordRevisions.map { it.revisionId }.toSet()
        require(auditEvents.all { it.revisionId in recordRevisionIds &&
            (it.priorRevisionId == null || it.priorRevisionId in recordRevisionIds) }) {
            "An audit event references a missing maintenance revision."
        }
        val auditRevisionIds = auditEvents.map { it.revisionId }
        require(auditRevisionIds.distinct().size == auditRevisionIds.size) {
            "A maintenance revision has multiple audit receipts."
        }
        require(recordRevisionIds == auditRevisionIds.toSet()) {
            "Every maintenance revision must have exactly one audit receipt."
        }
        val recordsByRevision = recordRevisions.associateBy { it.revisionId }
        require(auditEvents.all { event ->
            val revision = recordsByRevision[event.revisionId] ?: return@all false
            val expectedAction = when {
                revision.supersedesRevisionId == null -> MaintenanceAuditAction.CREATED
                revision.state == MaintenanceRecordState.VOIDED -> MaintenanceAuditAction.VOIDED
                else -> MaintenanceAuditAction.AMENDED
            }
            event.recordId == revision.recordId &&
                event.priorRevisionId == revision.supersedesRevisionId &&
                event.action == expectedAction &&
                event.actor == revision.actor &&
                event.recordedAt == revision.createdAt &&
                event.reason == revision.amendmentReason
        }) {
            "A maintenance audit receipt does not exactly match its record revision."
        }
    }

    fun referencedAttachmentSha256(): Set<String> = recordRevisions.asSequence()
        .flatMap { it.attachments.asSequence() }
        .filter { it.availability == AttachmentAvailability.AVAILABLE }
        .map { it.sha256 }
        .toSet()

    companion object {
        const val CONTRACT = "vehicle.maintenance-ledger-archive"
        const val CONTRACT_VERSION = "1.0.0"
        const val PAYLOAD_SCHEMA = "$CONTRACT@$CONTRACT_VERSION"
    }
}

/** Immutable SQLCipher-resident attachment body used by archive export and verified restore. */
class StoredMaintenanceAttachment(
    val sha256: String,
    val mediaType: String,
    bytes: ByteArray,
) {
    private val body = bytes.copyOf()
    val byteCount: Long get() = body.size.toLong()

    init {
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
        require(mediaType.matches(Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")))
        require(body.isNotEmpty())
    }

    fun bytes(): ByteArray = body.copyOf()
}

private fun requireUnique(values: List<String>, label: String) {
    require(values.distinct().size == values.size) { "Duplicate $label identity in archive." }
}

private fun <T> requireLinearHistories(
    values: List<T>,
    identity: (T) -> String,
    predecessor: (T) -> String?,
    group: (T) -> String,
    createdAt: (T) -> String,
    label: String,
) {
    values.groupBy(group).forEach { (_, history) ->
        val ids = history.map(identity).toSet()
        val root = history.singleOrNull { predecessor(it) == null }
        require(root != null) {
            "Each $label history needs exactly one root revision."
        }
        require(history.all { predecessor(it) == null || predecessor(it) in ids }) {
            "A $label revision predecessor is absent from the archive."
        }
        require(history.mapNotNull(predecessor).distinct().size == history.mapNotNull(predecessor).size) {
            "A $label revision has multiple successors."
        }
        val byId = history.associateBy(identity)
        require(history.all { revision ->
            predecessor(revision)?.let { predecessorId ->
                !Instant.parse(createdAt(revision)).isBefore(
                    Instant.parse(createdAt(requireNotNull(byId[predecessorId]))),
                )
            } ?: true
        }) {
            "A $label successor timestamp moves backwards."
        }
        val successorByPredecessor = history.mapNotNull { revision ->
            predecessor(revision)?.let { it to identity(revision) }
        }.toMap()
        val reachable = mutableSetOf<String>()
        var cursor: String? = identity(root)
        while (cursor != null) {
            require(reachable.add(cursor)) { "A $label history contains a cycle." }
            cursor = successorByPredecessor[cursor]
        }
        require(reachable == ids) {
            "A $label history contains a disconnected revision or cycle."
        }
    }
}
