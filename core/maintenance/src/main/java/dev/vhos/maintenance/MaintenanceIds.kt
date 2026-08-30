package dev.vhos.maintenance

import java.security.SecureRandom

/**
 * Secure, process-monotonic ULID identities for persisted maintenance-domain objects.
 *
 * The prefix is part of the type system: accidentally supplying a record revision where a
 * vehicle identity is required is rejected before it reaches SQLite. The 48-bit timestamp keeps
 * IDs sortable; the 80-bit random tail is incremented when multiple IDs are issued in one
 * millisecond (or if the wall clock moves backwards).
 */
object MaintenanceIds {
    private const val BODY_LENGTH = 26
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()
    private val secureRandom = SecureRandom()
    private var lastTimestampMillis = -1L
    private var lastEntropy = ByteArray(10)

    fun vehicle(): String = generate("veh")
    fun vehicleRevision(): String = generate("vehrev")
    fun maintenanceRecord(): String = generate("maintenance")
    fun maintenanceRevision(): String = generate("maintrev")
    fun component(): String = generate("component")
    fun componentRevision(): String = generate("comprev")
    fun lineItem(): String = generate("lineitem")
    fun measurement(): String = generate("measurement")
    fun attachment(): String = generate("attachment")
    fun auditEvent(): String = generate("audit")
    fun actor(): String = generate("actor")
    fun requirement(): String = generate("requirement")
    fun maintenanceTask(): String = generate("mainttask")
    fun maintenanceRule(): String = generate("maintrule")
    fun maintenanceDue(): String = generate("maintdue")
    fun maintenanceBaseline(): String = generate("maintbaseline")

    fun requireVehicle(value: String, field: String = "vehicle_id") =
        requireTyped(value, "veh", field)

    fun requireVehicleRevision(value: String, field: String = "vehicle_revision_id") =
        requireTyped(value, "vehrev", field)

    fun requireMaintenanceRecord(value: String, field: String = "record_id") =
        requireTyped(value, "maintenance", field)

    fun requireMaintenanceRevision(value: String, field: String = "revision_id") =
        requireTyped(value, "maintrev", field)

    fun requireComponent(value: String, field: String = "component_id") =
        requireTyped(value, "component", field)

    fun requireComponentRevision(value: String, field: String = "component_revision_id") =
        requireTyped(value, "comprev", field)

    fun requireLineItem(value: String, field: String = "line_item_id") =
        requireTyped(value, "lineitem", field)

    fun requireMeasurement(value: String, field: String = "measurement_id") =
        requireTyped(value, "measurement", field)

    fun requireAttachment(value: String, field: String = "attachment_id") =
        requireTyped(value, "attachment", field)

    fun requireAuditEvent(value: String, field: String = "audit_event_id") =
        requireTyped(value, "audit", field)

    fun requireActor(value: String, field: String = "actor_id") =
        requireTyped(value, "actor", field)

    fun requireRequirement(value: String, field: String = "requirement_id") =
        requireTyped(value, "requirement", field)

    fun requireMaintenanceTask(value: String, field: String = "task_id") =
        requireTyped(value, "mainttask", field)

    fun requireMaintenanceRule(value: String, field: String = "rule_id") =
        requireTyped(value, "maintrule", field)

    fun requireMaintenanceDue(value: String, field: String = "due_id") =
        requireTyped(value, "maintdue", field)

    fun requireMaintenanceBaseline(value: String, field: String = "baseline_id") =
        requireTyped(value, "maintbaseline", field)

    fun requireMaintenanceSourceManifest(value: String, field: String = "source_manifest_id") =
        requireTyped(value, "maintsource", field)

    fun requireSourceDocument(value: String, field: String = "source_document_id") =
        requireTyped(value, "source", field)

    fun requireTyped(value: String, prefix: String, field: String) {
        require(prefix.matches(Regex("^[a-z][a-z0-9]*$"))) { "Invalid persisted ID prefix." }
        require(value.matches(Regex("^${Regex.escape(prefix)}_[0-9A-HJKMNP-TV-Z]{$BODY_LENGTH}$"))) {
            "$field must be a typed $prefix ULID."
        }
        // A canonical 128-bit ULID cannot use more than the low three bits of its first symbol.
        require(value[prefix.length + 1] in '0'..'7') { "$field exceeds the ULID 128-bit range." }
    }

    @Synchronized
    private fun generate(prefix: String): String {
        val now = System.currentTimeMillis()
        val timestamp = maxOf(now, lastTimestampMillis)
        if (timestamp > lastTimestampMillis) {
            secureRandom.nextBytes(lastEntropy)
            lastTimestampMillis = timestamp
        } else if (!incrementEntropy()) {
            // An 80-bit overflow in one millisecond is practically unreachable. Advancing the
            // logical clock retains uniqueness and monotonic ordering without blocking a caller.
            lastTimestampMillis += 1
            secureRandom.nextBytes(lastEntropy)
        }
        return "${prefix}_${encode(lastTimestampMillis, lastEntropy)}"
    }

    private fun incrementEntropy(): Boolean {
        for (index in lastEntropy.lastIndex downTo 0) {
            val next = (lastEntropy[index].toInt() and 0xff) + 1
            lastEntropy[index] = next.toByte()
            if (next <= 0xff) return true
        }
        return false
    }

    private fun encode(timestampMillis: Long, entropy: ByteArray): String {
        require(timestampMillis in 0..0xffff_ffff_ffffL)
        require(entropy.size == 10)
        val bytes = ByteArray(16)
        for (index in 0 until 6) {
            bytes[index] = (timestampMillis ushr (40 - index * 8)).toByte()
        }
        entropy.copyInto(bytes, destinationOffset = 6)

        // ULID is 128 bits represented in 26 base32 characters (130 bits), with two leading zero
        // padding bits. A tiny bit reader avoids BigInteger and remains Android API compatible.
        val encoded = CharArray(BODY_LENGTH)
        for (characterIndex in encoded.indices) {
            var value = 0
            for (bitInCharacter in 0 until 5) {
                val paddedBitIndex = characterIndex * 5 + bitInCharacter
                val sourceBitIndex = paddedBitIndex - 2
                value = value shl 1
                if (sourceBitIndex >= 0) {
                    val sourceByte = sourceBitIndex / 8
                    val sourceOffset = 7 - (sourceBitIndex % 8)
                    value = value or ((bytes[sourceByte].toInt() ushr sourceOffset) and 1)
                }
            }
            encoded[characterIndex] = alphabet[value]
        }
        return encoded.concatToString()
    }
}
