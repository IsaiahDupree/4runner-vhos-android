package dev.vhos.model

object DeviceDisplayIdentity {
    const val OBD_BASE_NAME = "VHOS-4R-OBD"
    const val AC_BASE_NAME = "VHOS-4R-AC"

    fun obdName(advertisedName: String?, sourceId: String? = null): String {
        val suffix = sourceId.hardwareSuffix()
            ?: advertisedName.advertisedSuffix("VHOS-4R-OBD-", "VHOS-MRDIY-")
        return suffix?.let { "$OBD_BASE_NAME-$it" } ?: OBD_BASE_NAME
    }

    fun acName(advertisedName: String?, sourceId: String? = null): String {
        val suffix = sourceId.hardwareSuffix()
            ?: advertisedName.advertisedSuffix("VHOS-4R-AC-", "VHOS-AC-")
        return suffix?.let { "$AC_BASE_NAME-$it" } ?: AC_BASE_NAME
    }

    private fun String?.hardwareSuffix(): String? {
        val compact = this?.uppercase()?.filter { it.isHexadecimal() } ?: return null
        return compact.takeIf { it.length >= 6 }?.takeLast(6)
    }

    private fun String?.advertisedSuffix(vararg prefixes: String): String? {
        val value = this?.uppercase() ?: return null
        val prefix = prefixes.firstOrNull(value::startsWith) ?: return null
        return value.removePrefix(prefix)
            .takeIf { it.length == 6 && it.all { character -> character.isHexadecimal() } }
    }

    private fun Char.isHexadecimal(): Boolean = this in '0'..'9' || this in 'A'..'F'
}
