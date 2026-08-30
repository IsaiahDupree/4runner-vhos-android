package dev.vhos.headunit

import dev.vhos.maintenance.ConfigurationAttributeSource
import dev.vhos.maintenance.ConfigurationAttributeState
import dev.vhos.maintenance.ConfigurationResolutionStatus
import dev.vhos.maintenance.VehicleConfigurationAttribute
import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenancePlanUiTest {
    @Test
    fun configurationResolutionNeverTreatsUnknownAsResolved() {
        assertEquals(ConfigurationResolutionStatus.UNKNOWN, maintenanceConfigurationResolution(emptyList()))
        assertEquals(
            ConfigurationResolutionStatus.UNKNOWN,
            maintenanceConfigurationResolution(listOf(attribute("engine", ConfigurationAttributeState.UNKNOWN))),
        )
        assertEquals(
            ConfigurationResolutionStatus.PARTIAL,
            maintenanceConfigurationResolution(
                listOf(
                    attribute("engine", ConfigurationAttributeState.KNOWN, "2UZ-FE"),
                    attribute("driveline", ConfigurationAttributeState.UNKNOWN),
                ),
            ),
        )
        assertEquals(
            ConfigurationResolutionStatus.RESOLVED,
            maintenanceConfigurationResolution(
                listOf(
                    attribute("engine", ConfigurationAttributeState.KNOWN, "2UZ-FE"),
                    attribute("air_suspension", ConfigurationAttributeState.NOT_APPLICABLE),
                ),
            ),
        )
    }

    @Test
    fun intervalDecimalIsPositiveAndCanonical() {
        assertEquals("100", canonicalMaintenancePlanDecimal("100.000"))
        assertEquals("0.5", canonicalMaintenancePlanDecimal("0.50"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            canonicalMaintenancePlanDecimal("0")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            canonicalMaintenancePlanDecimal("not-a-number")
        }
    }

    private fun attribute(
        key: String,
        state: ConfigurationAttributeState,
        value: String? = null,
    ) = VehicleConfigurationAttribute(
        key = key,
        state = state,
        value = value,
        source = ConfigurationAttributeSource.MANUAL,
    ).validate()
}
