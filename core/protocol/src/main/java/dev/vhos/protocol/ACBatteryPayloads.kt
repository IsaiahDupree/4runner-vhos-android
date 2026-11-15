package dev.vhos.protocol

import com.google.gson.JsonParser
import dev.vhos.model.ACBatteryReading
import dev.vhos.model.ACBatteryResult

data class ACBatteryIdentity(val sourceId: String, val bootId: String, val buildId: String)

object ACBatteryPayloads {
    private val sourcePattern = Regex("ac-[0-9a-f]{12}")
    private val bootPattern = Regex("[0-9a-f]{16}")
    private val buildPattern = Regex("[0-9a-f]{12,64}")

    fun frame(raw: ByteArray, expected: MessageType): GatewayFrame {
        val frame = GatewayFrame.decode(raw, maximumPayloadBytes = 320)
        if (frame.messageType != expected || frame.flags != 0 || frame.protocolMinor != 0) {
            throw PayloadException("Unexpected A/C battery frame.")
        }
        return frame
    }

    fun identity(frame: GatewayFrame): ACBatteryIdentity = try {
        if (frame.messageType != MessageType.HANDSHAKE || frame.sequence != 1uL) {
            throw PayloadException("A/C battery handshake envelope is invalid.")
        }
        val fields = JsonParser.parseString(frame.payload.toString(Charsets.UTF_8)).asJsonObject
        fun string(name: String): String {
            val value = fields.getAsJsonPrimitive(name)
            require(value.isString)
            return value.asString
        }
        val source = string("source_id")
        val boot = string("boot_id")
        val build = string("firmware_build_id")
        val capabilities = fields.getAsJsonArray("capabilities").map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        }
        require(
            string("contract") == "sensor.node.battery.handshake" &&
                string("contract_version") == "1.0.0" &&
                string("role") == "AC_SENSOR" &&
                string("hardware_revision") == "AC_R8" &&
                string("protocol_version") == "1.0" &&
                capabilities.containsAll(listOf("battery.max17260", "battery.pack_ntc")) &&
                sourcePattern.matches(source) && bootPattern.matches(boot) && buildPattern.matches(build),
        )
        ACBatteryIdentity(source, boot, build)
    } catch (error: RuntimeException) {
        throw PayloadException("A/C battery identity failed validation.", error)
    }

    fun reading(frame: GatewayFrame): ACBatteryReading {
        val bytes = frame.payload
        if (frame.messageType != MessageType.AC_BATTERY ||
            bytes.size != 32 || bytes[0].toInt() != 1 || bytes[3].toInt() != 0
        ) {
            throw PayloadException("Invalid A/C battery payload.")
        }
        val flags = bytes[1].toUByte().toInt()
        val result = ACBatteryResult.entries.getOrNull(bytes[2].toUByte().toInt())
            ?: throw PayloadException("Unknown A/C battery result code.")
        val gauge = flags and 1 != 0
        val ntc = flags and 2 != 0
        if (flags > 3 || gauge != (result in setOf(ACBatteryResult.VALID, ACBatteryResult.PACK_TEMPERATURE_UNAVAILABLE)) ||
            ntc != (result == ACBatteryResult.VALID)
        ) {
            throw PayloadException("Inconsistent A/C battery validity flags.")
        }
        fun word(offset: Int) = bytes[offset].toUByte().toInt() or (bytes[offset + 1].toUByte().toInt() shl 8)
        fun signed(offset: Int) = word(offset).toShort().toDouble()
        fun time(offset: Int, usable: Boolean) = word(offset).takeIf { gauge && usable && it != 0xffff }?.times(5.625)
        val voltage = word(6) * 0.000078125
        val soc = word(12) / 256.0
        val average = signed(10) * 0.15625
        val temp = signed(26) / 100.0
        if ((gauge && (voltage !in 2.0..4.35 || soc > 100.0 || word(16) == 0 || word(4) and 2 != 0)) ||
            (ntc && temp !in -40.0..85.0)
        ) {
            throw PayloadException("A/C battery data is outside the supported pack profile.")
        }
        return ACBatteryReading(
            result = result,
            volts = voltage.takeIf { gauge },
            milliamps = (signed(8) * 0.15625).takeIf { gauge },
            averageMilliamps = average.takeIf { gauge },
            stateOfCharge = soc.takeIf { gauge },
            remainingMilliampHours = (word(14) / 2.0).takeIf { gauge },
            fullMilliampHours = (word(16) / 2.0).takeIf { gauge },
            healthPercent = (word(18) / 256.0).takeIf { gauge },
            equivalentCycles = (word(20) / 100.0).takeIf { gauge },
            secondsToEmpty = time(22, average < -5),
            secondsToFull = time(24, average > 5),
            packCelsius = temp.takeIf { ntc },
        )
    }
}
