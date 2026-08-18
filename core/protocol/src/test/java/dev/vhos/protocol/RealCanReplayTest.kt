package dev.vhos.protocol

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class RealCanReplayTest {
    @Test
    fun realCapturedCanFixtureIsPinnedAndRoundTripsTheDeployedLiveRecord() {
        val (raw, records) = loadFixture()

        assertEquals(FIXTURE_SHA256, sha256(raw))
        assertEquals(256, records.size)
        assertTrue(records.all { it.sessionId == 627_753_796u })
        assertTrue(records.all { it.listenOnly && it.bitrateBps == 500_000 })
        records.forEach { observation ->
            assertEquals(observation, CanObservation.decodeLive(observation.encodeLive()))
        }
    }

    @Test
    fun realCapturedCanFixtureSustainsTwentyReplaysAcrossHostileFragments() {
        val (_, fixture) = loadFixture()
        val expected = List(20) { fixture }.flatten()
        val wire = ByteArrayOutputStream().use { output ->
            expected.forEachIndexed { index, observation ->
                output.write(
                    GatewayFrame(
                        messageType = MessageType.RAW_CAN_FRAME,
                        sequence = (index + 1).toULong(),
                        monotonicMicroseconds = (index * 2_000).toULong(),
                        payload = observation.encodeLive(),
                    ).encode()
                )
            }
            output.toByteArray()
        }

        val sizes = intArrayOf(1, 3, 20, 244, 5, 509, 64, 17, 1_024)
        val decoder = FrameStreamDecoder()
        val decoded = mutableListOf<CanObservation>()
        var offset = 0
        var chunk = 0
        while (offset < wire.size) {
            val count = minOf(sizes[chunk % sizes.size], wire.size - offset)
            decoder.append(wire.copyOfRange(offset, offset + count)).forEach { frame ->
                decoded += frame.decodeCanObservations()
            }
            offset += count
            chunk++
        }

        assertEquals(5_120, decoded.size)
        assertEquals(expected, decoded)
        assertEquals(0L, decoder.recoveryCount)
        assertEquals(0L, decoder.discardedByteCount)
        assertEquals(0, decoder.bufferedByteCount)
    }

    private fun loadFixture(): Pair<ByteArray, List<CanObservation>> {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_NAME)) {
            "Real CAN replay fixture is missing."
        }.use { it.readBytes() }
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
        val records = raw.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map { gson.fromJson(it, FixtureObservation::class.java).toObservation() }
            .toList()
        return raw to records
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class FixtureObservation(
        val contract: String,
        val contractVersion: String,
        val gatewayId: String,
        val sessionId: Long,
        val sourceSequence: Long,
        val monotonicMicroseconds: Long,
        val bitrateBps: Int,
        val identifier: Long,
        val extended: Boolean,
        val remoteRequest: Boolean,
        val listenOnly: Boolean,
        val dataLength: Int,
        val data: List<Int>,
        val evidenceSource: String,
        val ingestedAt: String,
    ) {
        fun toObservation(): CanObservation {
            require(contract == "gateway.passive-can-observation" && contractVersion == "1.0.0")
            require(gatewayId == "esp32-9454c5b08d14")
            require(evidenceSource == "gateway-flash" && ingestedAt.isNotBlank())
            return CanObservation(
                sessionId = sessionId.toUInt(),
                sourceSequence = sourceSequence.toULong(),
                monotonicMicroseconds = monotonicMicroseconds.toULong(),
                bitrateBps = bitrateBps,
                identifier = identifier.toUInt(),
                extended = extended,
                remoteRequest = remoteRequest,
                listenOnly = listenOnly,
                dataLength = dataLength,
                data = data.map(Int::toByte).toByteArray(),
            )
        }
    }

    private companion object {
        const val FIXTURE_NAME = "real-can-2026-08-18-627753796-256.ndjson"
        const val FIXTURE_SHA256 = "af2305021c2d48d89c55d1739da407d78ee28baa39cce63125d0656672f58aed"
    }
}
