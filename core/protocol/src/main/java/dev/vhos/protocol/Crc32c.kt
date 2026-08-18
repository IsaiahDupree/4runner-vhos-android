package dev.vhos.protocol

object Crc32c {
    private const val POLYNOMIAL: UInt = 0x82F63B78u

    fun checksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): UInt {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var crc = UInt.MAX_VALUE
        for (index in offset until offset + length) {
            crc = crc xor bytes[index].toUByte().toUInt()
            repeat(8) {
                crc = (crc shr 1) xor if ((crc and 1u) == 1u) POLYNOMIAL else 0u
            }
        }
        return crc.inv()
    }
}
