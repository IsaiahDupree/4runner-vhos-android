package dev.vhos.ble

import dev.vhos.model.VehicleMotion
import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.GatewayHealth
import dev.vhos.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayCaptureLineageReducerTest {
    @Test
    fun liveRawCanUpdatesLineageWhileRetainedChunksNeverRefreshIt() {
        val empty = GatewayCaptureLineage()
        val retained = observation(sessionId = 12u, sequence = 88UL)
        assertEquals(
            empty,
            GatewayCaptureLineageReducer.acceptCan(
                current = empty,
                messageType = MessageType.CAPTURE_LOG_CHUNK,
                observations = listOf(retained),
                receivedAtEpochMillis = 1_000L,
            ),
        )

        val live = observation(sessionId = 73u, sequence = 901UL)
        val current = GatewayCaptureLineageReducer.acceptCan(
            current = empty,
            messageType = MessageType.RAW_CAN_FRAME,
            observations = listOf(live),
            receivedAtEpochMillis = 2_000L,
        )
        assertEquals(2_000L, current.lastVehicleFrameAtEpochMillis)
        assertEquals(73u, current.lastVehicleCanSessionId)
        assertEquals(901UL, current.lastVehicleCanSourceSequence)
        assertEquals(901_000UL, current.lastVehicleCanGatewayMonotonicMicroseconds)

        val afterDownload = GatewayCaptureLineageReducer.acceptCan(
            current = current,
            messageType = MessageType.CAPTURE_LOG_CHUNK,
            observations = listOf(observation(sessionId = 4u, sequence = 9_999UL)),
            receivedAtEpochMillis = 9_999L,
        )
        assertEquals(current, afterDownload)
        val nextConnection = GatewayCaptureLineage()
        assertNull(nextConnection.lastVehicleFrameAtEpochMillis)
        assertNull(nextConnection.lastVehicleCanSessionId)
        assertNull(nextConnection.captureActive)
        assertNull(nextConnection.gatewayCaptureSessionId)
    }

    @Test
    fun newestHealthReplacesCaptureStateAndMissingSessionClearsPriorLineage() {
        val active = GatewayCaptureLineageReducer.acceptHealth(
            GatewayCaptureLineage(),
            health(captureActive = true, captureSessionId = 73L),
        )
        assertEquals(true, active.captureActive)
        assertEquals(73u, active.gatewayCaptureSessionId)

        val stopped = GatewayCaptureLineageReducer.acceptHealth(
            active,
            health(captureActive = false, captureSessionId = null),
        )
        assertEquals(false, stopped.captureActive)
        assertNull(stopped.gatewayCaptureSessionId)
    }

    @Test
    fun malformedLiveInputsFailClosed() {
        val live = observation(sessionId = 73u, sequence = 901UL)
        assertThrows(IllegalArgumentException::class.java) {
            GatewayCaptureLineageReducer.acceptCan(
                GatewayCaptureLineage(),
                MessageType.RAW_CAN_FRAME,
                emptyList(),
                2_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GatewayCaptureLineageReducer.acceptCan(
                GatewayCaptureLineage(),
                MessageType.RAW_CAN_FRAME,
                listOf(live.copy(listenOnly = false)),
                2_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GatewayCaptureLineageReducer.acceptHealth(
                GatewayCaptureLineage(),
                health(captureActive = true, captureSessionId = 73L).copy(listenOnly = false),
            )
        }
    }

    private fun observation(sessionId: UInt, sequence: ULong) = CanObservation(
        sessionId = sessionId,
        sourceSequence = sequence,
        monotonicMicroseconds = sequence * 1_000UL,
        bitrateBps = 500_000,
        identifier = 0x2C4u,
        extended = false,
        remoteRequest = false,
        listenOnly = true,
        dataLength = 8,
        data = byteArrayOf(0x15, 0x6C, 0, 0, 0, 0, 0, 0),
    )

    private fun health(captureActive: Boolean, captureSessionId: Long?) = GatewayHealth(
        contract = "gateway.health",
        contractVersion = "1.0.0",
        observedAt = "2026-08-22T00:00:00Z",
        vehicleMotion = VehicleMotion.UNKNOWN,
        receivedFrames = 10,
        droppedFrames = 0,
        busErrorCount = 0,
        busOffCount = 0,
        storageFreeBytes = 1_024,
        captureActive = captureActive,
        captureSessionId = captureSessionId,
        listenOnly = true,
        canBitrateBps = 500_000,
        canPassiveLock = true,
        canScanState = "LOCKED_500K",
    )
}
