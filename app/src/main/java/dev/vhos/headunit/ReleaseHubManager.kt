package dev.vhos.headunit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.vhos.release.ReleaseArtifact
import dev.vhos.release.ReleaseArtifactKind
import dev.vhos.release.ReleaseCatalog
import dev.vhos.release.ReleaseCatalogCodec
import dev.vhos.release.ReleaseCatalogException
import dev.vhos.release.ReleaseTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

data class ReleaseHubSnapshot(
    val status: String = "Release catalog has not been checked.",
    val catalog: ReleaseCatalog? = null,
    val stagedApk: File? = null,
    val stagedArtifact: ReleaseArtifact? = null,
    val busy: Boolean = false,
)

class ReleaseHubManager(
    context: Context,
    private val onChange: (ReleaseHubSnapshot) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var snapshot = ReleaseHubSnapshot()

    fun refresh() = execute("Downloading signed release catalog…") {
        val catalogBytes = download(CATALOG_URL, 1_048_576)
        val signatureBytes = download(SIGNATURE_URL, 4_096)
        val publicKey = applicationContext.resources.openRawResource(
            R.raw.catalog_development_p256_public_der_base64
        ).use { it.readBytes() }
        val catalog = ReleaseCatalogCodec.verifyAndDecode(catalogBytes, signatureBytes, publicKey)
        update(
            snapshot.copy(
                status = "Verified ${catalog.artifacts.size} signed release entries.",
                catalog = catalog,
                busy = false,
            )
        )
    }

    fun stageAndroidUpdate() = execute("Downloading Android update…") {
        val catalog = snapshot.catalog ?: throw ReleaseCatalogException("Verify the release catalog first.")
        val artifact = catalog.artifacts.firstOrNull {
            it.target == ReleaseTarget.ANDROID_HEAD_UNIT && it.kind == ReleaseArtifactKind.ANDROID_APK
        } ?: throw ReleaseCatalogException("The signed catalog has no Android head-unit artifact.")
        val bytes = download(artifact.downloadUrl, artifact.byteCount)
        artifact.verifyDownloadedBytes(bytes)
        val directory = File(applicationContext.cacheDir, "verified-releases").apply { mkdirs() }
        val output = File(directory, "vhos-head-unit-${artifact.version}.apk")
        output.writeBytes(bytes)
        verifyApkIdentity(output, artifact)
        update(
            snapshot.copy(
                status = "Verified APK ${artifact.version}; Android installation approval is required.",
                stagedApk = output,
                stagedArtifact = artifact,
                busy = false,
            )
        )
    }

    fun requestInstall(activity: MainActivity) {
        val apk = snapshot.stagedApk ?: throw ReleaseCatalogException("Download and verify the Android APK first.")
        val artifact = snapshot.stagedArtifact ?: throw ReleaseCatalogException("Verified APK metadata is missing.")
        verifyApkIdentity(apk, artifact)
        if (Build.VERSION.SDK_INT >= 26 && !applicationContext.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${applicationContext.packageName}"))
            )
            update(snapshot.copy(status = "Allow installs from VHOS, then tap Install verified APK again."))
            return
        }
        val uri = FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.files",
            apk,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    fun current(): ReleaseHubSnapshot = snapshot

    override fun close() {
        executor.shutdownNow()
    }

    private fun execute(message: String, task: () -> Unit) {
        if (snapshot.busy) return
        update(snapshot.copy(status = message, busy = true))
        executor.execute {
            try {
                task()
            } catch (error: Exception) {
                update(snapshot.copy(status = error.message ?: error.javaClass.simpleName, busy = false))
            }
        }
    }

    private fun update(value: ReleaseHubSnapshot) {
        snapshot = value
        onChange(value)
    }

    private fun download(value: String, maximumBytes: Int): ByteArray {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host != "github.com") {
            throw ReleaseCatalogException("Release downloads are restricted to the signed GitHub host.")
        }
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode !in 200..299) {
                throw ReleaseCatalogException("Release download failed with HTTP ${connection.responseCode}.")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) throw ReleaseCatalogException("Release download exceeded its signed size boundary.")
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkIdentity(apk: File, artifact: ReleaseArtifact) {
        val expected = artifact.android ?: throw ReleaseCatalogException("Android signing metadata is missing.")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = applicationContext.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw ReleaseCatalogException("Android could not parse the downloaded APK.")
        if (info.packageName != expected.packageId) throw ReleaseCatalogException("APK package identity is not VHOS.")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (versionCode != expected.versionCode) throw ReleaseCatalogException("APK version code does not match the signed catalog.")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: throw ReleaseCatalogException("APK signing information is unavailable.")
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        val digests = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        if (expected.signingCertificateSha256 !in digests) {
            throw ReleaseCatalogException("APK signing certificate does not match the signed catalog.")
        }
    }

    companion object {
        private const val CATALOG_URL =
            "https://github.com/IsaiahDupree/4runner-vhos-release-hub/releases/latest/download/releases.json"
        private const val SIGNATURE_URL =
            "https://github.com/IsaiahDupree/4runner-vhos-release-hub/releases/latest/download/releases.json.sig"
    }
}
