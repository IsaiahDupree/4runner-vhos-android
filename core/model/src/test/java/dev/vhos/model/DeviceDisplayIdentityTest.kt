package dev.vhos.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDisplayIdentityTest {
    @Test
    fun `legacy OBD label is normalized`() {
        assertEquals(
            "VHOS-4R-OBD-B08D14",
            DeviceDisplayIdentity.obdName("VHOS-MRDIY-B08D14"),
        )
    }

    @Test
    fun `source identity wins after validation`() {
        assertEquals(
            "VHOS-4R-OBD-B08D14",
            DeviceDisplayIdentity.obdName("VHOS-MRDIY-000000", "esp32-aabbccb08d14"),
        )
    }

    @Test
    fun `transport UUID is never promoted to device name`() {
        assertEquals(
            "VHOS-4R-OBD",
            DeviceDisplayIdentity.obdName("54F7616F-F2E1-B2F1-47EC-763783505DEB"),
        )
    }
}
