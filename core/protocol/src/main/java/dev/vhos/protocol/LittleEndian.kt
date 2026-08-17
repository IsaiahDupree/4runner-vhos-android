package dev.vhos.protocol

internal fun ByteArray.u16(offset: Int): Int =
    this[offset].toUByte().toInt() or (this[offset + 1].toUByte().toInt() shl 8)

internal fun ByteArray.u32(offset: Int): UInt =
    this[offset].toUByte().toUInt() or
        (this[offset + 1].toUByte().toUInt() shl 8) or
        (this[offset + 2].toUByte().toUInt() shl 16) or
        (this[offset + 3].toUByte().toUInt() shl 24)

internal fun ByteArray.u64(offset: Int): ULong {
    var value = 0uL
    repeat(8) { index ->
        value = value or (this[offset + index].toUByte().toULong() shl (index * 8))
    }
    return value
}

internal fun ByteArray.putU32(offset: Int, value: UInt) {
    repeat(4) { index -> this[offset + index] = (value shr (index * 8)).toByte() }
}

internal fun ByteArray.putU64(offset: Int, value: ULong) {
    repeat(8) { index -> this[offset + index] = (value shr (index * 8)).toByte() }
}
