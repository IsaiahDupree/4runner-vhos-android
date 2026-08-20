package dev.vhos.discovery

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

data class CandidateValueSummary(
    val count: Int,
    val minimum: Double,
    val maximum: Double,
    val mean: Double,
    val standardDeviation: Double,
)

data class CandidateTransformEvaluation(
    val transformId: String,
    val unit: String,
    val sourceIds: List<String>,
    val summary: CandidateValueSummary,
)

data class SignalHypothesisEvaluation(
    val hypothesisId: String,
    val identifier: UInt,
    val identifierHex: String,
    val candidateSemantic: String?,
    val hypothesisStatus: String,
    val targetEvidenceStatus: String,
    val records: Int,
    val sessions: Int,
    val fieldValues: CandidateValueSummary?,
    val transformEvaluations: List<CandidateTransformEvaluation>,
    val sourceIds: List<String>,
    val requiredValidation: List<String>,
    val limitations: String,
    val productionValueDisplayAllowed: Boolean,
)

data class SignalHypothesisEvaluationReport(
    val contractVersion: String,
    val status: String,
    val promotionAllowed: Boolean,
    val acceptedSignalDefinitions: Int,
    val packId: String,
    val packVersion: String,
    val packSha256: String,
    val authority: String,
    val requiredBadge: String,
    val allowedSurface: String,
    val evaluations: List<SignalHypothesisEvaluation>,
)

class SignalHypothesisPack internal constructor(
    internal val document: PackDocument,
    val sha256: String,
) {
    val packId: String get() = document.packId
    val packVersion: String get() = document.packVersion
    val hypothesisCount: Int get() = document.hypotheses.size
    val acceptedSignalDefinitions: Int get() = document.authority.acceptedSignalDefinitions
}

object SignalHypothesisCatalog {
    const val CONTRACT = "can.signal-hypothesis-pack"
    const val CONTRACT_VERSION = "1.0.0"
    const val BUNDLED_RESOURCE =
        "vhos/vehicle-signal-packs/toyota-4runner-2005-passive-can-hypotheses.v1.json"
    const val BUNDLED_SHA256 =
        "6e2df8207e8977d613923a01f4bea7a16baba74a1869cce2ad0a83b56cf6ba32"

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    fun loadBundled(): SignalHypothesisPack {
        val raw = checkNotNull(
            SignalHypothesisCatalog::class.java.classLoader?.getResourceAsStream(BUNDLED_RESOURCE)
        ) { "The bundled Toyota signal hypothesis pack is missing." }.use { it.readBytes() }
        return parse(raw, expectedSha256 = BUNDLED_SHA256)
    }

    fun parse(raw: ByteArray, expectedSha256: String? = null): SignalHypothesisPack {
        require(raw.isNotEmpty()) { "Signal hypothesis pack is empty." }
        val digest = sha256(raw)
        expectedSha256?.let {
            require(digest == it.lowercase(Locale.US)) {
                "Signal hypothesis pack SHA-256 does not match the pinned release."
            }
        }
        val document = try {
            gson.fromJson(raw.toString(Charsets.UTF_8), PackDocument::class.java)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Signal hypothesis pack is not valid JSON.", error)
        }
        validate(document)
        return SignalHypothesisPack(document, digest)
    }

    private fun validate(pack: PackDocument) {
        require(pack.contract == CONTRACT && pack.contractVersion == CONTRACT_VERSION) {
            "Signal hypothesis pack contract is unsupported."
        }
        require(pack.packId.isNotBlank() && pack.packVersion.isNotBlank()) {
            "Signal hypothesis pack identity is incomplete."
        }
        require(pack.authority.status == "DISCOVERY_ONLY") {
            "Signal hypothesis pack must remain discovery-only."
        }
        require(pack.authority.acceptedSignalDefinitions == 0) {
            "Android cannot load a research pack containing accepted signal definitions."
        }
        require(!pack.authority.productionValueDisplayAllowed && !pack.authority.automaticPromotionAllowed) {
            "Signal hypothesis pack exceeds the Android engineering-research authority boundary."
        }
        require(pack.authority.statement.isNotBlank()) { "Signal hypothesis authority statement is required." }
        val sourceIds = pack.sources.map { it.sourceId }
        require(sourceIds.isNotEmpty() && sourceIds.none(String::isBlank) && sourceIds.distinct().size == sourceIds.size) {
            "Signal hypothesis sources must be present and unique."
        }
        val knownSources = sourceIds.toSet()
        val hypothesisIds = pack.hypotheses.map { it.hypothesisId }
        require(hypothesisIds.isNotEmpty() && hypothesisIds.none(String::isBlank) &&
            hypothesisIds.distinct().size == hypothesisIds.size
        ) { "Signal hypothesis identities must be present and unique." }
        val knownHypotheses = hypothesisIds.toSet()
        val transformsByHypothesis = mutableMapOf<String, Set<String>>()
        pack.hypotheses.forEach { hypothesis ->
            require(hypothesis.identifier in 0..0x1FFF_FFFF) { "Signal identifier is out of range." }
            val expectedIdentifier = if (hypothesis.extended) {
                String.format(Locale.US, "0x%08X", hypothesis.identifier)
            } else {
                String.format(Locale.US, "0x%03X", hypothesis.identifier)
            }
            require(hypothesis.identifierHex.equals(expectedIdentifier, ignoreCase = true)) {
                "Signal identifier text does not match its numeric value."
            }
            require(!hypothesis.productionValueDisplayAllowed) {
                "Research hypotheses cannot authorize production vehicle values."
            }
            val unknownHypothesisSources = hypothesis.sourceIds.filterNot(knownSources::contains)
            require(unknownHypothesisSources.isEmpty()) {
                "Signal hypothesis ${hypothesis.hypothesisId} has missing or unknown sources: " +
                    unknownHypothesisSources.joinToString()
            }
            if (hypothesis.sourceIds.isEmpty()) {
                require(hypothesis.status == "UNMAPPED" && hypothesis.candidateSemantic == null &&
                    hypothesis.field == null && hypothesis.candidateTransforms.isEmpty()
                ) { "Only a source-free unmapped identifier may omit source references." }
            }
            hypothesis.field?.let(::validateField)
            val transformIds = hypothesis.candidateTransforms.map { it.transformId }
            require(transformIds.none(String::isBlank) && transformIds.distinct().size == transformIds.size) {
                "Candidate transform identities must be unique."
            }
            hypothesis.candidateTransforms.forEach { transform ->
                require(transform.scale.isFinite() && transform.offset.isFinite() && transform.unit.isNotBlank()) {
                    "Candidate transform is incomplete or non-finite."
                }
                val unknownTransformSources = transform.sourceIds.filterNot(knownSources::contains)
                require(transform.sourceIds.isNotEmpty() && unknownTransformSources.isEmpty()) {
                    "Candidate transform ${transform.transformId} has missing or unknown sources: " +
                        unknownTransformSources.joinToString()
                }
            }
            transformsByHypothesis[hypothesis.hypothesisId] = transformIds.toSet()
        }
        val relationshipIds = pack.relationships.map { it.relationshipId }
        require(relationshipIds.distinct().size == relationshipIds.size) {
            "Signal relationship identities must be unique."
        }
        pack.relationships.forEach { relationship ->
            require(!relationship.productionValueDisplayAllowed) {
                "Research relationships cannot authorize production vehicle values."
            }
            require(relationship.leftHypothesisId in knownHypotheses &&
                relationship.rightHypothesisId in knownHypotheses
            ) { "Signal relationship references an unknown hypothesis." }
            require(relationship.leftTransformId in transformsByHypothesis.getValue(relationship.leftHypothesisId) &&
                relationship.rightTransformId in transformsByHypothesis.getValue(relationship.rightHypothesisId)
            ) { "Signal relationship references an unknown transform." }
        }
    }

    private fun validateField(field: CandidateField) {
        require(field.byteOffset in 0..7 && field.byteLength in 1..8 &&
            field.byteOffset + field.byteLength <= 8
        ) { "Candidate field exceeds an eight-byte CAN payload." }
        require(field.endianness == "BIG" || field.endianness == "LITTLE") {
            "Candidate field byte order is unsupported."
        }
        require(field.mask > 0 && field.rightShift >= 0) { "Candidate field mask is invalid." }
        field.signedBits?.let {
            require(it in 1 until 64 && it <= field.byteLength * 8) {
                "Candidate signed width is invalid."
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

object SignalHypothesisEvaluator {
    const val REPORT_VERSION = "1.0.0"
    const val STATUS = "DISCOVERY_ONLY"
    const val REQUIRED_BADGE = "UNVERIFIED CROSS-MODEL HYPOTHESIS"
    const val ALLOWED_SURFACE = "ENGINEERING_RESEARCH"

    fun evaluate(
        input: List<DiscoveryObservation>,
        pack: SignalHypothesisPack,
    ): SignalHypothesisEvaluationReport {
        require(input.isNotEmpty()) { "No persisted CAN observations are available." }
        require(input.all { it.sourceId.isNotBlank() }) { "CAN source identity is required." }
        require(input.all { it.observation.listenOnly }) {
            "Every hypothesis input must retain listen-only proof."
        }
        val duplicateIdentity = input.groupingBy {
            Triple(it.sourceId, it.observation.sessionId, it.observation.sourceSequence)
        }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateIdentity == null) { "Duplicate CAN observation identity is not analyzable." }

        val byIdentifier = input.groupBy { it.observation.identifier.toInt() to it.observation.extended }
        val evaluations = pack.document.hypotheses.map { hypothesis ->
            val matching = byIdentifier[hypothesis.identifier to hypothesis.extended].orEmpty()
            val extracted = hypothesis.field?.let { field ->
                matching.mapNotNull { record -> extract(record, field)?.toDouble() }
            }.orEmpty()
            val evidenceStatus = when {
                matching.isEmpty() -> "ABSENT"
                hypothesis.field == null -> "ID_PRESENT"
                extracted.distinct().size <= 1 -> "FIELD_PRESENT_STATIC"
                else -> "FIELD_PRESENT_DYNAMIC"
            }
            SignalHypothesisEvaluation(
                hypothesisId = hypothesis.hypothesisId,
                identifier = hypothesis.identifier.toUInt(),
                identifierHex = hypothesis.identifierHex,
                candidateSemantic = hypothesis.candidateSemantic,
                hypothesisStatus = hypothesis.status,
                targetEvidenceStatus = evidenceStatus,
                records = matching.size,
                sessions = matching.map {
                    Triple(it.sourceId, it.observation.sessionId, it.observation.extended)
                }.toSet().size,
                fieldValues = extracted.takeIf(List<Double>::isNotEmpty)?.let(::summary),
                transformEvaluations = hypothesis.candidateTransforms.mapNotNull { transform ->
                    extracted.takeIf(List<Double>::isNotEmpty)?.let { rawValues ->
                        CandidateTransformEvaluation(
                            transformId = transform.transformId,
                            unit = transform.unit,
                            sourceIds = transform.sourceIds,
                            summary = summary(rawValues.map { it * transform.scale + transform.offset }),
                        )
                    }
                },
                sourceIds = hypothesis.sourceIds,
                requiredValidation = hypothesis.requiredValidation,
                limitations = hypothesis.limitations,
                productionValueDisplayAllowed = false,
            )
        }
        return SignalHypothesisEvaluationReport(
            contractVersion = REPORT_VERSION,
            status = STATUS,
            promotionAllowed = false,
            acceptedSignalDefinitions = pack.acceptedSignalDefinitions,
            packId = pack.packId,
            packVersion = pack.packVersion,
            packSha256 = pack.sha256,
            authority = pack.document.authority.statement,
            requiredBadge = REQUIRED_BADGE,
            allowedSurface = ALLOWED_SURFACE,
            evaluations = evaluations,
        )
    }

    private fun extract(record: DiscoveryObservation, field: CandidateField): Long? {
        if (field.byteOffset + field.byteLength > record.observation.dataLength) return null
        var value = 0L
        if (field.endianness == "BIG") {
            repeat(field.byteLength) { index ->
                value = (value shl 8) or byte(record, field.byteOffset + index).toLong()
            }
        } else {
            repeat(field.byteLength) { index ->
                value = value or (byte(record, field.byteOffset + index).toLong() shl (index * 8))
            }
        }
        value = (value and field.mask) ushr field.rightShift
        field.signedBits?.let { bits ->
            val widthMask = (1L shl bits) - 1L
            val sign = 1L shl (bits - 1)
            value = value and widthMask
            if (value and sign != 0L) value -= 1L shl bits
        }
        return value
    }

    private fun byte(record: DiscoveryObservation, index: Int): Int =
        record.observation.data[index].toUByte().toInt()

    private fun summary(values: List<Double>): CandidateValueSummary {
        val mean = values.average()
        val variance = values.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / values.size
        return CandidateValueSummary(
            count = values.size,
            minimum = values.min(),
            maximum = values.max(),
            mean = mean,
            standardDeviation = sqrt(variance),
        )
    }
}

internal data class PackDocument(
    val contract: String = "",
    val contractVersion: String = "",
    val packId: String = "",
    val packVersion: String = "",
    val authority: PackAuthority = PackAuthority(),
    val sources: List<PackSource> = emptyList(),
    val hypotheses: List<PackHypothesis> = emptyList(),
    val relationships: List<PackRelationship> = emptyList(),
)

internal data class PackAuthority(
    val status: String = "",
    val acceptedSignalDefinitions: Int = -1,
    val productionValueDisplayAllowed: Boolean = true,
    val automaticPromotionAllowed: Boolean = true,
    val statement: String = "",
)

internal data class PackSource(val sourceId: String = "")

internal data class PackHypothesis(
    val hypothesisId: String = "",
    val identifier: Int = -1,
    val identifierHex: String = "",
    val extended: Boolean = false,
    val candidateSemantic: String? = null,
    val status: String = "",
    val field: CandidateField? = null,
    val candidateTransforms: List<CandidateTransform> = emptyList(),
    val sourceIds: List<String> = emptyList(),
    val productionValueDisplayAllowed: Boolean = true,
    val requiredValidation: List<String> = emptyList(),
    val limitations: String = "",
)

internal data class CandidateField(
    val byteOffset: Int = -1,
    val byteLength: Int = -1,
    val endianness: String = "",
    val mask: Long = 0,
    val rightShift: Int = -1,
    val signedBits: Int? = null,
)

internal data class CandidateTransform(
    val transformId: String = "",
    val scale: Double = Double.NaN,
    val offset: Double = Double.NaN,
    val unit: String = "",
    val sourceIds: List<String> = emptyList(),
)

internal data class PackRelationship(
    val relationshipId: String = "",
    val leftHypothesisId: String = "",
    val leftTransformId: String = "",
    val rightHypothesisId: String = "",
    val rightTransformId: String = "",
    val productionValueDisplayAllowed: Boolean = true,
)
