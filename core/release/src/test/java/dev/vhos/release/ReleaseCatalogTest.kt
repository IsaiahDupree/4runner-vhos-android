package dev.vhos.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ReleaseCatalogTest {
    private val artifactBytes = "real-artifact".toByteArray()
    private val catalogBytes = """
        {"artifacts":[{"android":{"debug_signed":true,"package_id":"dev.vhos.headunit","signing_certificate_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version_code":1},"artifact_id":"android-test","byte_count":13,"channel":"DEVELOPMENT","download_url":"https://github.com/owner/repo/releases/download/v1/app.apk","install_method":"ANDROID_PACKAGE_INSTALLER","kind":"ANDROID_APK","published_at":"2026-08-17T12:00:00Z","readiness":"AVAILABLE","release_notes":"Test artifact.","sha256":"${ReleaseCatalogCodec.sha256(artifactBytes)}","source_commit":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","source_repository":"https://github.com/owner/repo","target":"ANDROID_HEAD_UNIT","version":"1.0.0"}],"catalog_id":"B80D8EBC-8CC1-4290-A50D-06E72CAEAE13","contract":"vhos.release-catalog","contract_version":"1.0.0","generated_at":"2026-08-17T12:00:00Z"}
    """.trimIndent().toByteArray()

    @Test
    fun verifiesCatalogAndArtifactIntegrity() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(catalogBytes)
        }.sign()
        val catalog = ReleaseCatalogCodec.verifyAndDecode(
            catalogBytes,
            Base64.getEncoder().encode(signature),
            Base64.getEncoder().encode(keyPair.public.encoded),
        )
        assertEquals("android-test", catalog.artifacts.single().artifactId)
        catalog.artifacts.single().verifyDownloadedBytes(artifactBytes)
        assertThrows(ReleaseCatalogException::class.java) {
            catalog.artifacts.single().verifyDownloadedBytes("wrong-content".toByteArray())
        }
    }

    @Test
    fun rejectsCatalogSignedByAnotherKey() {
        val signer = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val verifier = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signer.private)
            update(catalogBytes)
        }.sign()
        assertThrows(ReleaseCatalogException::class.java) {
            ReleaseCatalogCodec.verifyAndDecode(
                catalogBytes,
                Base64.getEncoder().encode(signature),
                Base64.getEncoder().encode(verifier.public.encoded),
            )
        }
    }
}
