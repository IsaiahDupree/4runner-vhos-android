package dev.vhos.discovery

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import dev.vhos.protocol.CanObservation
import java.security.MessageDigest

internal object RealCanFixture {
    const val SHA256 = "af2305021c2d48d89c55d1739da407d78ee28baa39cce63125d0656672f58aed"
    private const val RESOURCE = "real-can-2026-08-18-627753796-256.ndjson"

    fun load(owner: Class<*>): List<DiscoveryObservation> {
        val raw = checkNotNull(owner.classLoader?.getResourceAsStream(RESOURCE)) {
            "Real CAN replay fixture is missing."
        }.use { it.readBytes() }
        check(sha256(raw) == SHA256) { "Real CAN replay fixture SHA-256 changed." }
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
        return raw.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map { gson.fromJson(it, Document::class.java).toDiscoveryObservation() }
            .toList()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class Document(
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
        fun toDiscoveryObservation(): DiscoveryObservation {
            require(contract == "gateway.passive-can-observation" && contractVersion == "1.0.0")
            require(evidenceSource == "gateway-flash" && ingestedAt.isNotBlank())
            return DiscoveryObservation(
                sourceId = gatewayId,
                observation = CanObservation(
                    sessionId = sessionId.toUInt(),
                    sourceSequence = sourceSequence.toULong(),
                    monotonicMicroseconds = monotonicMicroseconds.toULong(),
                    bitrateBps = bitrateBps,
                    identifier = identifier.toUInt(),
                    extended = extended,
                    remoteRequest = remoteRequest,
                    listenOnly = listenOnly,
                    dataLength = dataLength,
                    data = data.map { it.toByte() }.toByteArray(),
                ),
            )
        }
    }
}
