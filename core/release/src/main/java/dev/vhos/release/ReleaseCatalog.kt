package dev.vhos.release

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

enum class ReleaseTarget { ANDROID_HEAD_UNIT, ESP32_OBD_GATEWAY, ESP32_AC_SENSOR_NODE }
enum class ReleaseArtifactKind { ANDROID_APK, ESP32_VHOSOTA, ESP32_MERGED_RECOVERY }
enum class ReleaseChannel { DEVELOPMENT, BETA, STABLE }
enum class ReleaseInstallMethod {
    ANDROID_PACKAGE_INSTALLER,
    MOBILE_TO_ESP32_AUTHENTICATED_WIFI,
    USB_SERIAL_INITIAL_FLASH,
}
enum class ReleaseReadiness { AVAILABLE, SAFETY_GATED, RECOVERY_ONLY }

data class AndroidReleaseMetadata(
    val packageId: String,
    val versionCode: Long,
    val signingCertificateSha256: String,
    val debugSigned: Boolean,
)

data class Esp32ReleaseMetadata(
    val chipFamily: String,
    val hardwareRevisions: List<String>,
    val distributionSignature: String,
    val initialFlashRequired: Boolean,
)

data class ReleaseArtifact(
    val artifactId: String,
    val target: ReleaseTarget,
    val kind: ReleaseArtifactKind,
    val version: String,
    val channel: ReleaseChannel,
    val publishedAt: String,
    val sourceRepository: String,
    val sourceCommit: String,
    val downloadUrl: String,
    val sha256: String,
    val byteCount: Int,
    val installMethod: ReleaseInstallMethod,
    val readiness: ReleaseReadiness,
    val releaseNotes: String,
    val android: AndroidReleaseMetadata?,
    val esp32: Esp32ReleaseMetadata?,
) {
    fun verifyDownloadedBytes(bytes: ByteArray) {
        if (bytes.size != byteCount) throw ReleaseCatalogException("Artifact byte count does not match the signed catalog.")
        if (!ReleaseCatalogCodec.sha256(bytes).equals(sha256, ignoreCase = true)) {
            throw ReleaseCatalogException("Artifact SHA-256 does not match the signed catalog.")
        }
    }
}

data class ReleaseCatalog(
    val contract: String,
    val contractVersion: String,
    val catalogId: String,
    val generatedAt: String,
    val artifacts: List<ReleaseArtifact>,
)

class ReleaseCatalogException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object ReleaseCatalogCodec {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .disableHtmlEscaping()
        .create()
    private val hashPattern = Regex("^[0-9a-f]{64}$")
    private val commitPattern = Regex("^[0-9a-f]{40}$")
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{0,119}$")

    fun verifyAndDecode(
        catalogBytes: ByteArray,
        signatureBase64: ByteArray,
        publicKeyDerBase64: ByteArray,
    ): ReleaseCatalog = try {
        if (catalogBytes.size > 1_048_576) throw ReleaseCatalogException("Release catalog exceeds its size limit.")
        val signatureBytes = Base64.getMimeDecoder().decode(signatureBase64)
        val publicKeyBytes = Base64.getMimeDecoder().decode(publicKeyDerBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(catalogBytes)
        }
        if (!verifier.verify(signatureBytes)) throw ReleaseCatalogException("Release catalog signature is invalid.")
        val catalog = gson.fromJson(catalogBytes.toString(Charsets.UTF_8), ReleaseCatalog::class.java)
            ?: throw ReleaseCatalogException("Release catalog JSON is empty.")
        validate(catalog)
        catalog
    } catch (error: ReleaseCatalogException) {
        throw error
    } catch (error: RuntimeException) {
        throw ReleaseCatalogException("Release catalog is invalid.", error)
    } catch (error: Exception) {
        throw ReleaseCatalogException("Release catalog trust verification failed.", error)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun validate(catalog: ReleaseCatalog) {
        if (catalog.contract != "vhos.release-catalog" || catalog.contractVersion != "1.0.0" ||
            catalog.artifacts.size > 100 || catalog.artifacts.map { it.artifactId }.toSet().size != catalog.artifacts.size
        ) throw ReleaseCatalogException("Release catalog contract is unsupported or internally inconsistent.")
        Instant.parse(catalog.generatedAt)
        catalog.artifacts.forEach(::validateArtifact)
    }

    private fun validateArtifact(artifact: ReleaseArtifact) {
        if (!idPattern.matches(artifact.artifactId) || artifact.version.isBlank() || artifact.version.length > 80 ||
            !hashPattern.matches(artifact.sha256) || !commitPattern.matches(artifact.sourceCommit) ||
            artifact.byteCount !in 1..134_217_728 || artifact.releaseNotes.isBlank() || artifact.releaseNotes.length > 1_000
        ) throw ReleaseCatalogException("Invalid release metadata for ${artifact.artifactId}.")
        Instant.parse(artifact.publishedAt)
        listOf(artifact.sourceRepository, artifact.downloadUrl).forEach { value ->
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host != "github.com") {
                throw ReleaseCatalogException("Untrusted release URL for ${artifact.artifactId}.")
            }
        }
        when (artifact.target) {
            ReleaseTarget.ANDROID_HEAD_UNIT -> if (
                artifact.kind != ReleaseArtifactKind.ANDROID_APK ||
                artifact.installMethod != ReleaseInstallMethod.ANDROID_PACKAGE_INSTALLER ||
                artifact.android?.packageId != "dev.vhos.headunit" ||
                artifact.android.signingCertificateSha256.matches(hashPattern).not() ||
                artifact.esp32 != null
            ) throw ReleaseCatalogException("Android trust metadata is invalid for ${artifact.artifactId}.")
            ReleaseTarget.ESP32_OBD_GATEWAY -> if (
                artifact.kind != ReleaseArtifactKind.ESP32_VHOSOTA ||
                artifact.installMethod != ReleaseInstallMethod.MOBILE_TO_ESP32_AUTHENTICATED_WIFI ||
                artifact.esp32?.distributionSignature != "ED25519" || artifact.android != null
            ) throw ReleaseCatalogException("OBD firmware metadata is invalid for ${artifact.artifactId}.")
            ReleaseTarget.ESP32_AC_SENSOR_NODE -> if (
                artifact.kind != ReleaseArtifactKind.ESP32_MERGED_RECOVERY ||
                artifact.installMethod != ReleaseInstallMethod.USB_SERIAL_INITIAL_FLASH ||
                artifact.readiness != ReleaseReadiness.RECOVERY_ONLY ||
                artifact.esp32?.initialFlashRequired != true || artifact.android != null
            ) throw ReleaseCatalogException("A/C recovery metadata is invalid for ${artifact.artifactId}.")
        }
    }
}
