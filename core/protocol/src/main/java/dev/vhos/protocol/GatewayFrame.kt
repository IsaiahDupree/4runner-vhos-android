package dev.vhos.protocol

enum class MessageType(val code: Int) {
    HANDSHAKE(1),
    RAW_CAN_FRAME(2),
    DIAGNOSTIC_RESPONSE(3),
    GATEWAY_HEALTH(4),
    CAPTURE_MARKER(5),
    ALLOWLISTED_DIAGNOSTIC_REQUEST(6),
    EXPERIMENT_PLAN(7),
    OTA_CONTROL(8),
    AGENT_HANDOFF_ACKNOWLEDGEMENT(9),
    EXPERIMENT_RESULT(10),
    CAPTURE_LOG_REQUEST(11),
    CAPTURE_LOG_INDEX(12),
    CAPTURE_LOG_CHUNK(13);

    companion object {
        fun fromCode(code: Int): MessageType = entries.firstOrNull { it.code == code }
            ?: throw FrameException.UnsupportedMessageType(code)
    }
}

sealed class FrameException(message: String) : IllegalArgumentException(message) {
    data object IncompleteHeader : FrameException("Gateway frame header is incomplete.")
    data object InvalidMagic : FrameException("Gateway frame magic is invalid.")
    data class UnsupportedProtocolMajor(val major: Int) :
        FrameException("Unsupported gateway protocol major $major.")
    data class UnsupportedMessageType(val code: Int) :
        FrameException("Unsupported gateway message type $code.")
    data class PayloadTooLarge(val bytes: Int) :
        FrameException("Gateway payload exceeds the configured limit: $bytes bytes.")
    data class IncompletePayload(val expected: Int, val actual: Int) :
        FrameException("Gateway payload is incomplete: expected $expected bytes, received $actual.")
    data class TrailingBytes(val expected: Int, val actual: Int) :
        FrameException("Gateway frame has trailing bytes: expected $expected bytes, received $actual.")
    data class HeaderCrc(val expected: UInt, val actual: UInt) :
        FrameException("Gateway header CRC32C mismatch.")
    data class PayloadCrc(val expected: UInt, val actual: UInt) :
        FrameException("Gateway payload CRC32C mismatch.")
}

data class GatewayFrame(
    val protocolMajor: Int = 1,
    val protocolMinor: Int = 0,
    val messageType: MessageType,
    val flags: Int = 0,
    val sequence: ULong,
    val monotonicMicroseconds: ULong,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is GatewayFrame &&
        protocolMajor == other.protocolMajor && protocolMinor == other.protocolMinor &&
        messageType == other.messageType && flags == other.flags && sequence == other.sequence &&
        monotonicMicroseconds == other.monotonicMicroseconds && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * sequence.hashCode() + payload.contentHashCode()

    fun encode(maximumPayloadBytes: Int = DEFAULT_MAXIMUM_PAYLOAD_BYTES): ByteArray {
        require(protocolMajor == 1)
        require(protocolMinor in 0..255 && flags in 0..255)
        require(payload.size <= maximumPayloadBytes)
        val bytes = ByteArray(HEADER_LENGTH + payload.size)
        MAGIC.copyInto(bytes)
        bytes[4] = protocolMajor.toByte()
        bytes[5] = protocolMinor.toByte()
        bytes[6] = messageType.code.toByte()
        bytes[7] = flags.toByte()
        bytes.putU32(8, payload.size.toUInt())
        bytes.putU64(12, sequence)
        bytes.putU64(20, monotonicMicroseconds)
        bytes.putU32(28, Crc32c.checksum(payload))
        bytes.putU32(32, Crc32c.checksum(bytes, 0, 32))
        payload.copyInto(bytes, HEADER_LENGTH)
        return bytes
    }

    companion object {
        val MAGIC = byteArrayOf(0x56, 0x48, 0x4F, 0x53)
        const val HEADER_LENGTH = 36
        const val DEFAULT_MAXIMUM_PAYLOAD_BYTES = 1_048_576

        fun decode(
            bytes: ByteArray,
            maximumPayloadBytes: Int = DEFAULT_MAXIMUM_PAYLOAD_BYTES,
            allowTrailingBytes: Boolean = false,
        ): GatewayFrame {
            if (bytes.size < HEADER_LENGTH) throw FrameException.IncompleteHeader
            if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) throw FrameException.InvalidMagic
            val major = bytes[4].toUByte().toInt()
            if (major != 1) throw FrameException.UnsupportedProtocolMajor(major)
            val payloadLength = bytes.u32(8).toLong()
            if (payloadLength > maximumPayloadBytes) {
                throw FrameException.PayloadTooLarge(payloadLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            val expectedLength = HEADER_LENGTH + payloadLength.toInt()
            if (bytes.size < expectedLength) throw FrameException.IncompletePayload(expectedLength, bytes.size)
            if (!allowTrailingBytes && bytes.size != expectedLength) {
                throw FrameException.TrailingBytes(expectedLength, bytes.size)
            }
            val expectedHeaderCrc = bytes.u32(32)
            val actualHeaderCrc = Crc32c.checksum(bytes, 0, 32)
            if (expectedHeaderCrc != actualHeaderCrc) {
                throw FrameException.HeaderCrc(expectedHeaderCrc, actualHeaderCrc)
            }
            val payload = bytes.copyOfRange(HEADER_LENGTH, expectedLength)
            val expectedPayloadCrc = bytes.u32(28)
            val actualPayloadCrc = Crc32c.checksum(payload)
            if (expectedPayloadCrc != actualPayloadCrc) {
                throw FrameException.PayloadCrc(expectedPayloadCrc, actualPayloadCrc)
            }
            return GatewayFrame(
                protocolMajor = major,
                protocolMinor = bytes[5].toUByte().toInt(),
                messageType = MessageType.fromCode(bytes[6].toUByte().toInt()),
                flags = bytes[7].toUByte().toInt(),
                sequence = bytes.u64(12),
                monotonicMicroseconds = bytes.u64(20),
                payload = payload,
            )
        }
    }
}

class FrameStreamDecoder(
    private val maximumPayloadBytes: Int = GatewayFrame.DEFAULT_MAXIMUM_PAYLOAD_BYTES,
) {
    private var buffered = ByteArray(0)
    var discardedByteCount: Long = 0
        private set
    var recoveryCount: Long = 0
        private set
    var corruptCandidateCount: Long = 0
        private set
    val bufferedByteCount: Int
        @Synchronized get() = buffered.size

    @Synchronized
    fun append(chunk: ByteArray): List<GatewayFrame> {
        if (chunk.isNotEmpty()) buffered += chunk
        val frames = mutableListOf<GatewayFrame>()
        while (true) {
            if (!alignToMagic()) break
            if (buffered.size < GatewayFrame.HEADER_LENGTH) break

            val payloadLength = buffered.u32(8).toLong()
            if (!headerIsValid(0, payloadLength)) {
                corruptCandidateCount++
                discardPrefix(1)
                continue
            }
            val frameLength = GatewayFrame.HEADER_LENGTH + payloadLength.toInt()
            if (buffered.size < frameLength) {
                val nextHeader = nextValidHeaderOffset(GatewayFrame.MAGIC.size)
                if (nextHeader != null) {
                    corruptCandidateCount++
                    discardPrefix(nextHeader)
                    continue
                }
                val nextMagic = findMagic(GatewayFrame.MAGIC.size)
                if (nextMagic >= 0) {
                    corruptCandidateCount++
                    discardPrefix(nextMagic)
                }
                break
            }
            val frameBytes = buffered.copyOfRange(0, frameLength)
            try {
                frames += GatewayFrame.decode(frameBytes, maximumPayloadBytes)
                buffered = buffered.copyOfRange(frameLength, buffered.size)
            } catch (_: FrameException) {
                corruptCandidateCount++
                val nextHeader = nextValidHeaderOffset(1)
                if (nextHeader != null) {
                    discardPrefix(nextHeader)
                } else {
                    val nextMagic = findMagic(1)
                    if (nextMagic >= 0) discardPrefix(nextMagic)
                    else discardUnframedBytesPreservingMagicPrefix()
                    break
                }
            }
        }
        return frames
    }

    @Synchronized
    fun reset() {
        buffered = ByteArray(0)
        discardedByteCount = 0
        recoveryCount = 0
        corruptCandidateCount = 0
    }

    @Synchronized
    fun resetBufferPreservingDiagnostics() {
        if (buffered.isNotEmpty()) {
            discardedByteCount += buffered.size
            recoveryCount++
            buffered = ByteArray(0)
        }
    }

    private fun alignToMagic(): Boolean {
        if (buffered.isEmpty()) return false
        if (startsWithMagic(0)) return true
        val next = findMagic(1)
        if (next >= 0) {
            discardPrefix(next)
            return true
        }
        discardUnframedBytesPreservingMagicPrefix()
        return false
    }

    private fun headerIsValid(offset: Int, payloadLength: Long? = null): Boolean {
        if (offset < 0 || buffered.size - offset < GatewayFrame.HEADER_LENGTH) return false
        if (!startsWithMagic(offset)) return false
        if (buffered[offset + 4].toUByte().toInt() != 1) return false
        if (MessageType.entries.none { it.code == buffered[offset + 6].toUByte().toInt() }) return false
        val length = payloadLength ?: buffered.u32(offset + 8).toLong()
        if (length > maximumPayloadBytes) return false
        val expected = buffered.u32(offset + 32)
        val actual = Crc32c.checksum(buffered, offset, 32)
        return expected == actual
    }

    private fun nextValidHeaderOffset(start: Int): Int? {
        var candidate = findMagic(start)
        while (candidate >= 0) {
            if (buffered.size - candidate < GatewayFrame.HEADER_LENGTH) return null
            if (headerIsValid(candidate)) return candidate
            candidate = findMagic(candidate + 1)
        }
        return null
    }

    private fun findMagic(start: Int): Int {
        if (start < 0 || buffered.size < GatewayFrame.MAGIC.size) return -1
        val last = buffered.size - GatewayFrame.MAGIC.size
        for (index in start..last) {
            if (startsWithMagic(index)) return index
        }
        return -1
    }

    private fun startsWithMagic(offset: Int): Boolean {
        if (offset < 0 || offset + GatewayFrame.MAGIC.size > buffered.size) return false
        return GatewayFrame.MAGIC.indices.all { buffered[offset + it] == GatewayFrame.MAGIC[it] }
    }

    private fun discardPrefix(count: Int) {
        if (count <= 0) return
        val bounded = count.coerceAtMost(buffered.size)
        buffered = buffered.copyOfRange(bounded, buffered.size)
        discardedByteCount += bounded
        recoveryCount++
    }

    private fun discardUnframedBytesPreservingMagicPrefix() {
        val maximumSuffix = minOf(GatewayFrame.MAGIC.size - 1, buffered.size)
        var suffix = 0
        for (count in maximumSuffix downTo 1) {
            val matches = (0 until count).all { index ->
                buffered[buffered.size - count + index] == GatewayFrame.MAGIC[index]
            }
            if (matches) {
                suffix = count
                break
            }
        }
        discardPrefix(buffered.size - suffix)
    }
}
