package dev.vhos.protocol

import dev.vhos.model.DeviceRole
import dev.vhos.model.VehicleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPayloadsTest {
    private val validHandshake =
        """{"active_config_id":"passive","active_config_version":"0.4.0","capabilities":["capture.passive","evidence.export"],"contract":"gateway.handshake","contract_version":"1.0.0","firmware_build_id":"abc","firmware_version":"0.1.0","gateway_id":"esp32-test","hardware_revision":"MrDIY","listen_only":true,"protocol_version":"1.0.0"}"""
    private val validHealth =
        """{"contract":"gateway.health","contract_version":"1.0.0","observed_at":"2026-08-22T00:00:00Z","vehicle_motion":"PARKED","received_frames":12,"dropped_frames":0,"bus_error_count":0,"bus_off_count":0,"storage_free_bytes":1024,"capture_active":true,"capture_session_id":73,"listen_only":true,"can_bitrate_bps":500000,"can_passive_lock":true,"can_scan_state":"LOCKED_500K"}"""

    @Test
    fun validatesPhysicalObdIdentityAndSafetyContract() {
        val identity = PayloadContracts.decodeAndValidateHandshake(validHandshake.toByteArray())
        assertEquals(DeviceRole.OBD_CAN, identity.role)
        assertEquals("esp32-test", identity.sourceId)
        assertTrue(identity.listenOnly)
    }

    @Test
    fun rejectsHandshakeWithoutListenOnlyProof() {
        val unsafe = validHandshake.replace("\"listen_only\":true", "\"listen_only\":false")
        assertThrows(PayloadException::class.java) {
            PayloadContracts.decodeAndValidateHandshake(unsafe.toByteArray())
        }
    }

    @Test
    fun missingIdentityFieldIsAContractErrorInsteadOfAnAppCrash() {
        val incomplete = validHandshake.replace("\"gateway_id\":\"esp32-test\",", "")
        assertThrows(PayloadException::class.java) {
            PayloadContracts.decodeAndValidateHandshake(incomplete.toByteArray())
        }
    }

    @Test
    fun decodesDeterministicParkedAuthorityFromValidatedHealth() {
        val health = PayloadContracts.decodeHealth(validHealth.toByteArray())
        assertEquals(VehicleMotion.PARKED, health.vehicleMotion)
        assertEquals(73L, health.captureSessionId)
        assertTrue(health.captureActive)
    }

    @Test
    fun rejectsUnknownMotionWireValuesInsteadOfWideningSafetyAuthority() {
        val invalid = validHealth.replace("\"PARKED\"", "\"STOPPED\"")
        assertThrows(PayloadException::class.java) {
            PayloadContracts.decodeHealth(invalid.toByteArray())
        }
    }

    @Test
    fun rejectsCaptureSessionOutsideDeployedUint32Range() {
        listOf("-1", "4294967296").forEach { invalid ->
            assertThrows(PayloadException::class.java) {
                PayloadContracts.decodeHealth(
                    validHealth.replace("\"capture_session_id\":73", "\"capture_session_id\":$invalid")
                        .toByteArray()
                )
            }
        }
    }
}
