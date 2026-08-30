package dev.vhos.ble

import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedLiveCanReducerTest {
    @Test
    fun onlyPersistedLiveRawCanEntersTheBoundedRuntimeProjection() {
        val rpm = realObservation(
            identifier = 0x2C4u,
            sequence = 3_793UL,
            monotonicMicroseconds = 8_959_637UL,
            data = intArrayOf(5, 226, 0, 31, 64, 128, 18, 166),
        )
        val unpersisted = PersistedLiveCanReducer.accept(
            current = emptyList(),
            messageType = MessageType.RAW_CAN_FRAME,
            sourceId = SOURCE_ID,
            observation = rpm,
            receivedAtEpochMs = 10_000L,
            persisted = false,
        )
        assertTrue(unpersisted.isEmpty())

        val retainedDownload = PersistedLiveCanReducer.accept(
            current = emptyList(),
            messageType = MessageType.CAPTURE_LOG_CHUNK,
            sourceId = SOURCE_ID,
            observation = rpm,
            receivedAtEpochMs = 10_000L,
            persisted = true,
        )
        assertTrue(retainedDownload.isEmpty())

        val accepted = PersistedLiveCanReducer.accept(
            current = emptyList(),
            messageType = MessageType.RAW_CAN_FRAME,
            sourceId = SOURCE_ID,
            observation = rpm,
            receivedAtEpochMs = 10_000L,
            persisted = true,
        )
        assertEquals(1, accepted.size)
        assertEquals(0x2C4u, accepted.single().identifier)
        assertEquals(listOf(5, 226, 0, 31, 64, 128, 18, 166), accepted.single().data)
    }

    @Test
    fun latestPerIdentifierReplacementAndBoundUseRealCaptureRecords() {
        val first = realObservation(
            identifier = 0x025u,
            sequence = 197UL,
            monotonicMicroseconds = 2_210_613UL,
            data = intArrayOf(0, 39, 0, 13, 255, 255, 255, 94),
        )
        val second = realObservation(
            identifier = 0x2C4u,
            sequence = 3_683UL,
            monotonicMicroseconds = 8_748_195UL,
            data = intArrayOf(5, 245, 0, 31, 64, 128, 18, 185),
        )
        val third = realObservation(
            identifier = 0x2D0u,
            sequence = 211UL,
            monotonicMicroseconds = 2_232_406UL,
            data = intArrayOf(0, 0, 8, 0, 32, 0, 0, 2),
        )
        val initial = listOf(first, second).foldIndexed(emptyList<dev.vhos.model.PersistedLiveCanObservation>()) {
                index, state, observation ->
            PersistedLiveCanReducer.accept(
                current = state,
                messageType = MessageType.RAW_CAN_FRAME,
                sourceId = SOURCE_ID,
                observation = observation,
                receivedAtEpochMs = 20_000L + index,
                persisted = true,
                maximumIdentifiers = 2,
            )
        }
        val bounded = PersistedLiveCanReducer.accept(
            current = initial,
            messageType = MessageType.RAW_CAN_FRAME,
            sourceId = SOURCE_ID,
            observation = third,
            receivedAtEpochMs = 20_002L,
            persisted = true,
            maximumIdentifiers = 2,
        )
        assertEquals(listOf(0x2C4u, 0x2D0u), bounded.map { it.identifier })

        val newestRpm = realObservation(
            identifier = 0x2C4u,
            sequence = 3_793UL,
            monotonicMicroseconds = 8_959_637UL,
            data = intArrayOf(5, 226, 0, 31, 64, 128, 18, 166),
        )
        val replaced = PersistedLiveCanReducer.accept(
            current = bounded,
            messageType = MessageType.RAW_CAN_FRAME,
            sourceId = SOURCE_ID,
            observation = newestRpm,
            receivedAtEpochMs = 20_003L,
            persisted = true,
            maximumIdentifiers = 2,
        )
        assertEquals(2, replaced.size)
        assertEquals(3_793UL, replaced.single { it.identifier == 0x2C4u }.sourceSequence)
    }

    @Test
    fun invalidListenOnlyProofFailsClosed() {
        val observation = realObservation(
            identifier = 0x2C4u,
            sequence = 3_793UL,
            monotonicMicroseconds = 8_959_637UL,
            data = intArrayOf(5, 226, 0, 31, 64, 128, 18, 166),
        ).copy(listenOnly = false)
        assertThrows(IllegalArgumentException::class.java) {
            PersistedLiveCanReducer.accept(
                current = emptyList(),
                messageType = MessageType.RAW_CAN_FRAME,
                sourceId = SOURCE_ID,
                observation = observation,
                receivedAtEpochMs = 10_000L,
                persisted = true,
            )
        }
    }

    private fun realObservation(
        identifier: UInt,
        sequence: ULong,
        monotonicMicroseconds: ULong,
        data: IntArray,
    ) = CanObservation(
        sessionId = 627_753_796u,
        sourceSequence = sequence,
        monotonicMicroseconds = monotonicMicroseconds,
        bitrateBps = 500_000,
        identifier = identifier,
        extended = false,
        remoteRequest = false,
        listenOnly = true,
        dataLength = 8,
        data = data.map(Int::toByte).toByteArray(),
    )

    private companion object {
        const val SOURCE_ID = "esp32-9454c5b08d14"
    }
}
