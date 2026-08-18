package dev.vhos.protocol

data class CanObservation(
    val sessionId: UInt,
    val sourceSequence: ULong,
    val monotonicMicroseconds: ULong,
    val bitrateBps: Int,
    val identifier: UInt,
    val extended: Boolean,
    val remoteRequest: Boolean,
    val listenOnly: Boolean,
    val dataLength: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is CanObservation &&
        sessionId == other.sessionId && sourceSequence == other.sourceSequence &&
        monotonicMicroseconds == other.monotonicMicroseconds && bitrateBps == other.bitrateBps &&
        identifier == other.identifier && extended == other.extended &&
        remoteRequest == other.remoteRequest && listenOnly == other.listenOnly &&
        dataLength == other.dataLength && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * sourceSequence.hashCode() + data.contentHashCode()

    /** Rebuild the deployed live record for explicitly labeled historical replay. */
    fun encodeLive(): ByteArray {
        require(dataLength in 0..8 && data.size == 8) {
            "CAN observation data shape is invalid: DLC $dataLength, storage ${data.size} bytes."
        }
        require(bitrateBps == 250_000 || bitrateBps == 500_000) {
            "Unsupported CAN bitrate: $bitrateBps."
        }
        return ByteArray(RECORD_BYTES).also { bytes ->
            bytes[0] = 1
            bytes[1] = (
                (if (extended) 0x01 else 0) or
                    (if (remoteRequest) 0x02 else 0) or
                    (if (listenOnly) 0x04 else 0)
                ).toByte()
            bytes[2] = dataLength.toByte()
            bytes[3] = if (bitrateBps == 250_000) 2 else 1
            bytes.putU32(4, identifier)
            bytes.putU64(8, sourceSequence)
            bytes.putU64(16, monotonicMicroseconds)
            bytes.putU32(24, sessionId)
            data.copyInto(bytes, 28)
        }
    }

    companion object {
        const val RECORD_BYTES = 36

        fun decodeLive(payload: ByteArray): CanObservation {
            if (payload.size != RECORD_BYTES || payload[0].toInt() != 1) {
                throw PayloadException("Invalid live CAN record length/version: ${payload.size} bytes.")
            }
            return decodeFields(payload, payload.u32(24), 28)
        }

        fun decodeStored(record: ByteArray, sessionId: UInt): CanObservation {
            if (record.size != RECORD_BYTES || record[0].toInt() != 1) {
                throw PayloadException("Invalid stored CAN record length/version: ${record.size} bytes.")
            }
            val expected = record.u32(32)
            val actual = Crc32c.checksum(record, 0, 32)
            if (expected != actual) throw PayloadException("Stored CAN record CRC32C mismatch.")
            return decodeFields(record, sessionId, 24)
        }

        private fun decodeFields(bytes: ByteArray, sessionId: UInt, dataOffset: Int): CanObservation {
            val flags = bytes[1].toUByte().toInt()
            val length = bytes[2].toUByte().toInt().coerceAtMost(8)
            return CanObservation(
                sessionId = sessionId,
                sourceSequence = bytes.u64(8),
                monotonicMicroseconds = bytes.u64(16),
                bitrateBps = if (bytes[3].toInt() == 2) 250_000 else 500_000,
                identifier = bytes.u32(4),
                extended = flags and 0x01 != 0,
                remoteRequest = flags and 0x02 != 0,
                listenOnly = flags and 0x04 != 0,
                dataLength = length,
                data = bytes.copyOfRange(dataOffset, dataOffset + 8),
            )
        }
    }
}

data class CaptureLogChunk(
    val slot: Int,
    val endOfFile: Boolean,
    val recordOffset: UInt,
    val sessionId: UInt,
    val records: List<CanObservation>,
) {
    companion object {
        private const val HEADER_BYTES = 16

        fun decode(payload: ByteArray): CaptureLogChunk {
            if (payload.size < HEADER_BYTES || payload[0].toInt() != 1) {
                throw PayloadException("Capture-log chunk header is invalid.")
            }
            val count = payload.u16(8)
            val recordBytes = payload.u16(10)
            if (recordBytes != CanObservation.RECORD_BYTES) {
                throw PayloadException("Unsupported capture record size: $recordBytes.")
            }
            val expectedBytes = HEADER_BYTES + count * recordBytes
            if (payload.size != expectedBytes) {
                throw PayloadException(
                    "Capture-log chunk length mismatch: expected $expectedBytes, received ${payload.size}."
                )
            }
            val sessionId = payload.u32(12)
            val records = (0 until count).map { index ->
                val offset = HEADER_BYTES + index * recordBytes
                CanObservation.decodeStored(
                    payload.copyOfRange(offset, offset + recordBytes),
                    sessionId,
                )
            }
            return CaptureLogChunk(
                slot = payload[1].toUByte().toInt(),
                endOfFile = payload[2].toInt() == 1,
                recordOffset = payload.u32(4),
                sessionId = sessionId,
                records = records,
            )
        }
    }
}

fun GatewayFrame.decodeCanObservations(): List<CanObservation> = when (messageType) {
    MessageType.RAW_CAN_FRAME -> listOf(CanObservation.decodeLive(payload))
    MessageType.CAPTURE_LOG_CHUNK -> CaptureLogChunk.decode(payload).records
    else -> emptyList()
}

data class CaptureLogRequest(
    val operation: Operation,
    val slot: Int = 0,
    val recordOffset: UInt = 0u,
) {
    enum class Operation(val code: Int) { INDEX(0), READ(1), ROTATE(2), PAUSE(3), RESUME(4) }

    fun encode(): ByteArray = ByteArray(8).also { bytes ->
        require(slot in 0..255)
        bytes[0] = 1
        bytes[1] = operation.code.toByte()
        bytes[2] = slot.toByte()
        bytes.putU32(4, recordOffset)
    }
}
