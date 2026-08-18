package dev.vhos.protocol

import java.time.Instant
import java.util.Locale

data class J1979ResponseEvidence(
    val gatewayId: String,
    val captureId: String,
    val observedAt: String,
    val gatewayMonotonicMicroseconds: ULong,
    val sourceSequence: ULong,
    val transport: String,
    val ecuAddress: String,
    val requestPid: Int,
    val responsePayload: ByteArray,
) {
    init {
        require(gatewayId.isNotBlank())
        require(captureId.isNotBlank())
        require(requestPid in 0..255)
        require(responsePayload.size >= 2)
        require(responsePayload[0].toUByte().toInt() == POSITIVE_MODE_01_RESPONSE)
        require(responsePayload[1].toUByte().toInt() == requestPid)
    }

    val data: ByteArray get() = responsePayload.copyOfRange(2, responsePayload.size)

    companion object {
        const val WIRE_BYTES = 36
        private const val POSITIVE_MODE_01_RESPONSE = 0x41

        fun decodePassiveWire(
            payload: ByteArray,
            gatewayId: String,
            observedAt: String = Instant.now().toString(),
        ): J1979ResponseEvidence {
            if (payload.size != WIRE_BYTES || payload[0].toUByte().toInt() != 1) {
                throw PayloadException("Passive J1979 evidence has an invalid wire length or version.")
            }
            val transport = when (payload[1].toUByte().toInt()) {
                1 -> "ISO_15765_11_500"
                2 -> "ISO_15765_11_250"
                else -> throw PayloadException("Passive J1979 evidence has an unsupported transport.")
            }
            val responseLength = payload[2].toUByte().toInt()
            if (responseLength !in 2..7) {
                throw PayloadException("Passive J1979 response length must be between 2 and 7 bytes.")
            }
            val ecu = payload.u32(4)
            if (ecu !in 0x7E8u..0x7EFu) {
                throw PayloadException("Passive J1979 response ECU is outside 0x7E8–0x7EF.")
            }
            val response = payload.copyOfRange(28, 28 + responseLength)
            if (response[0].toUByte().toInt() != POSITIVE_MODE_01_RESPONSE) {
                throw PayloadException("Passive J1979 evidence is not a positive Mode 01 response.")
            }
            return J1979ResponseEvidence(
                gatewayId = gatewayId,
                captureId = "capture-${payload.u32(24)}",
                observedAt = observedAt,
                gatewayMonotonicMicroseconds = payload.u64(16),
                sourceSequence = payload.u64(8),
                transport = transport,
                ecuAddress = String.format(Locale.US, "0x%03X", ecu.toInt()),
                requestPid = response[1].toUByte().toInt(),
                responsePayload = response,
            )
        }
    }
}

data class J1979ECUAvailability(
    val ecuAddress: String,
    val queriedBasePids: List<Int>,
    val supportedPids: List<Int>,
    val enumerationComplete: Boolean,
    val incompleteReason: String?,
)

data class J1979StandardSample(
    val gatewayId: String,
    val captureId: String,
    val ecuAddress: String,
    val observedAt: String,
    val gatewayMonotonicMicroseconds: ULong,
    val sourceSequence: ULong,
    val pid: Int,
    val signalId: String,
    val name: String,
    val rawDataHex: String,
    val value: Double,
    val unit: String,
    val definitionRevision: String,
)

class J1979Accumulator {
    private val bitmapsByEcu = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val supportedByEcu = mutableMapOf<String, MutableSet<Int>>()
    private val samplesByIdentity = linkedMapOf<String, J1979StandardSample>()
    private var contextKey: String? = null

    val availability: List<J1979ECUAvailability>
        get() = bitmapsByEcu.keys.sorted().map { ecu ->
            val bitmaps = bitmapsByEcu.getValue(ecu)
            val supported = supportedByEcu[ecu].orEmpty()
            val completeness = completeness(bitmaps, supported)
            J1979ECUAvailability(
                ecuAddress = ecu,
                queriedBasePids = bitmaps.keys.sorted(),
                supportedPids = supported.sorted(),
                enumerationComplete = completeness.first,
                incompleteReason = completeness.second,
            )
        }

    val standardSamples: List<J1979StandardSample>
        get() = samplesByIdentity.values.sortedWith(
            compareBy<J1979StandardSample> { it.gatewayMonotonicMicroseconds }.thenBy { it.signalId }
        )

    fun ingest(response: J1979ResponseEvidence): J1979StandardSample? {
        val incomingContext = "${response.gatewayId}|${response.captureId}|${response.transport}"
        if (contextKey != incomingContext) {
            bitmapsByEcu.clear()
            supportedByEcu.clear()
            samplesByIdentity.clear()
            contextKey = incomingContext
        }
        val data = response.data
        if (response.requestPid in SUPPORTED_BASE_PIDS) {
            if (data.size != 4) {
                throw PayloadException("Supported-PID response must contain exactly four bitmap bytes.")
            }
            bitmapsByEcu.getOrPut(response.ecuAddress, ::mutableMapOf)[response.requestPid] = data
            supportedByEcu.getOrPut(response.ecuAddress, ::mutableSetOf).addAll(
                decodeSupportedBitmap(response.requestPid, data)
            )
            return null
        }
        val definition = DEFINITIONS[response.requestPid] ?: return null
        val ecuAvailability = availability.firstOrNull { it.ecuAddress == response.ecuAddress }
            ?: return null
        if (!ecuAvailability.enumerationComplete || response.requestPid !in ecuAvailability.supportedPids) {
            return null
        }
        if (data.size < definition.byteCount) {
            throw PayloadException(
                "PID 0x${response.requestPid.toString(16).uppercase()} requires ${definition.byteCount} data bytes."
            )
        }
        val relevant = data.copyOfRange(0, definition.byteCount)
        var raw = 0uL
        relevant.forEach { raw = (raw shl 8) or it.toUByte().toULong() }
        val value = raw.toDouble() * definition.multiplier / definition.divisor + definition.offset
        val identity = listOf(
            response.gatewayId,
            response.captureId,
            response.ecuAddress,
            response.gatewayMonotonicMicroseconds,
            response.sourceSequence,
            response.requestPid,
        ).joinToString(":")
        return J1979StandardSample(
            gatewayId = response.gatewayId,
            captureId = response.captureId,
            ecuAddress = response.ecuAddress,
            observedAt = response.observedAt,
            gatewayMonotonicMicroseconds = response.gatewayMonotonicMicroseconds,
            sourceSequence = response.sourceSequence,
            pid = response.requestPid,
            signalId = definition.signalId,
            name = definition.name,
            rawDataHex = relevant.joinToString("") { "%02X".format(Locale.US, it.toUByte().toInt()) },
            value = value,
            unit = definition.unit,
            definitionRevision = DEFINITION_REVISION,
        ).also { samplesByIdentity[identity] = it }
    }

    companion object {
        const val DEFINITION_REVISION = "d3259214a9e0340c4a6cff9ec5f8ff5953eee6f2"
        private val SUPPORTED_BASE_PIDS = (0x00..0xE0 step 0x20).toSet()

        fun decodeSupportedBitmap(basePid: Int, bitmap: ByteArray): List<Int> {
            if (basePid !in SUPPORTED_BASE_PIDS || bitmap.size != 4) {
                throw PayloadException("Supported-PID bitmap requires a valid 0x20 base and four bytes.")
            }
            var value = 0u
            bitmap.forEach { value = (value shl 8) or it.toUByte().toUInt() }
            return (1..32).mapNotNull { offset ->
                val candidate = basePid + offset
                if (candidate <= 0xFF && value and (1u shl (32 - offset)) != 0u) {
                    candidate
                } else {
                    null
                }
            }
        }

        private fun completeness(
            bitmaps: Map<Int, ByteArray>,
            supported: Set<Int>,
        ): Pair<Boolean, String?> {
            if (0x00 !in bitmaps) return false to "PID 0x00 availability response is missing."
            var base = 0x00
            while (base < 0xE0) {
                val continuation = base + 0x20
                if (continuation !in supported) return true to null
                if (continuation !in bitmaps) {
                    return false to "PID 0x${continuation.toString(16).padStart(2, '0').uppercase()} availability response is required."
                }
                base = continuation
            }
            return true to null
        }

        private data class Definition(
            val signalId: String,
            val name: String,
            val byteCount: Int,
            val multiplier: Double,
            val divisor: Double,
            val offset: Double,
            val unit: String,
        )

        private val DEFINITIONS = mapOf(
            0x04 to Definition("obd.engine.calculated_load", "Calculated engine load", 1, 100.0, 255.0, 0.0, "%"),
            0x05 to Definition("obd.engine.coolant_temperature", "Engine coolant temperature", 1, 1.0, 1.0, -40.0, "degC"),
            0x06 to Definition("obd.engine.short_fuel_trim_bank1", "Short-term fuel trim bank 1", 1, 100.0, 128.0, -100.0, "%"),
            0x07 to Definition("obd.engine.long_fuel_trim_bank1", "Long-term fuel trim bank 1", 1, 100.0, 128.0, -100.0, "%"),
            0x08 to Definition("obd.engine.short_fuel_trim_bank2", "Short-term fuel trim bank 2", 1, 100.0, 128.0, -100.0, "%"),
            0x09 to Definition("obd.engine.long_fuel_trim_bank2", "Long-term fuel trim bank 2", 1, 100.0, 128.0, -100.0, "%"),
            0x0A to Definition("obd.engine.fuel_pressure", "Fuel pressure", 1, 3.0, 1.0, 0.0, "kPa"),
            0x0B to Definition("obd.engine.intake_manifold_pressure", "Intake manifold absolute pressure", 1, 1.0, 1.0, 0.0, "kPa"),
            0x0C to Definition("obd.engine.speed", "Engine speed", 2, 1.0, 4.0, 0.0, "rpm"),
            0x0D to Definition("obd.vehicle.speed", "Vehicle speed", 1, 1.0, 1.0, 0.0, "km/h"),
            0x0E to Definition("obd.engine.timing_advance", "Timing advance", 1, 1.0, 2.0, -64.0, "deg"),
            0x0F to Definition("obd.engine.intake_air_temperature", "Intake air temperature", 1, 1.0, 1.0, -40.0, "degC"),
            0x10 to Definition("obd.engine.mass_air_flow", "Mass air flow", 2, 1.0, 100.0, 0.0, "g/s"),
            0x11 to Definition("obd.engine.throttle_position", "Absolute throttle position", 1, 100.0, 255.0, 0.0, "%"),
            0x1F to Definition("obd.engine.run_time", "Time since engine start", 2, 1.0, 1.0, 0.0, "s"),
        )
    }
}
