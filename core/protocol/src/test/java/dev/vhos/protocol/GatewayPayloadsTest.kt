package dev.vhos.protocol

import dev.vhos.model.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPayloadsTest {
    private val validHandshake =
        """{"active_config_id":"passive","active_config_version":"0.4.0","capabilities":["capture.passive","evidence.export"],"contract":"gateway.handshake","contract_version":"1.0.0","firmware_build_id":"abc","firmware_version":"0.1.0","gateway_id":"esp32-test","hardware_revision":"MrDIY","listen_only":true,"protocol_version":"1.0.0"}"""

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
}
