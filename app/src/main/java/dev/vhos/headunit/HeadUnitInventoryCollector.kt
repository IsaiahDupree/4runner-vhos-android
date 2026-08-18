package dev.vhos.headunit

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.DisplayMetrics
import dev.vhos.digitaltwin.AndroidRuntime
import dev.vhos.digitaltwin.HeadUnitApplication
import dev.vhos.digitaltwin.HeadUnitCapabilities
import dev.vhos.digitaltwin.HeadUnitDisplay
import dev.vhos.digitaltwin.HeadUnitHardware
import dev.vhos.digitaltwin.HeadUnitInventory
import dev.vhos.digitaltwin.PermissionState
import dev.vhos.digitaltwin.UnknownSourceInstallState
import java.io.File

object HeadUnitInventoryCollector {
    fun capture(activity: Activity): HeadUnitInventory {
        val packageManager = activity.packageManager
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = StatFs(activity.filesDir.absolutePath)
        val display = displayMetrics(activity)
        val density = display.density.takeIf { it > 0f } ?: 1f

        return HeadUnitInventory(
            application = HeadUnitApplication(
                packageId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                installerPackage = installerPackage(activity),
            ),
            hardware = HeadUnitHardware(
                manufacturer = Build.MANUFACTURER.nonBlank("unknown-manufacturer"),
                model = Build.MODEL.nonBlank("unknown-model"),
                device = Build.DEVICE.nonBlank("unknown-device"),
                product = Build.PRODUCT.nonBlank("unknown-product"),
                board = Build.BOARD.nonBlank("unknown-board"),
                hardware = Build.HARDWARE.nonBlank("unknown-hardware"),
                cpuDescriptor = cpuDescriptor(),
                supportedAbis = Build.SUPPORTED_ABIS.map(String::trim).filter(String::isNotEmpty)
                    .ifEmpty { listOf("unknown-abi") },
                logicalCpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                totalRamBytes = memory.totalMem.coerceAtLeast(1),
                availableRamBytes = memory.availMem.coerceIn(0, memory.totalMem.coerceAtLeast(1)),
                totalInternalStorageBytes = storage.totalBytes.coerceAtLeast(1),
                freeInternalStorageBytes = storage.availableBytes.coerceIn(0, storage.totalBytes.coerceAtLeast(1)),
                lowRamDevice = activityManager.isLowRamDevice,
            ),
            android = AndroidRuntime(
                release = Build.VERSION.RELEASE.nonBlank("unknown"),
                apiLevel = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
                buildFingerprint = Build.FINGERPRINT.nonBlank("unknown-fingerprint"),
            ),
            display = HeadUnitDisplay(
                widthPixels = display.widthPixels.coerceAtLeast(1),
                heightPixels = display.heightPixels.coerceAtLeast(1),
                densityDpi = display.densityDpi.coerceAtLeast(1),
                widthDp = (display.widthPixels / density).toInt().coerceAtLeast(1),
                heightDp = (display.heightPixels / density).toInt().coerceAtLeast(1),
            ),
            capabilities = HeadUnitCapabilities(
                bleFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                bluetoothScanPermission = permissionState(
                    activity,
                    Manifest.permission.BLUETOOTH_SCAN,
                    minimumApi = 31,
                ),
                bluetoothConnectPermission = permissionState(
                    activity,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    minimumApi = 31,
                ),
                notificationPermission = permissionState(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                    minimumApi = 33,
                ),
                unknownSourceInstall = when {
                    Build.VERSION.SDK_INT < 26 -> UnknownSourceInstallState.UNAVAILABLE
                    packageManager.canRequestPackageInstalls() -> UnknownSourceInstallState.ALLOWED
                    else -> UnknownSourceInstallState.OWNER_APPROVAL_REQUIRED
                },
                batteryOptimizationExempt =
                    (activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(activity.packageName),
            ),
        ).validate()
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(activity: Activity): DisplayMetrics = DisplayMetrics().also { metrics ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.density = activity.resources.displayMetrics.density
            metrics.densityDpi = activity.resources.displayMetrics.densityDpi
        } else {
            activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        }
    }

    private fun permissionState(
        activity: Activity,
        permission: String,
        minimumApi: Int,
    ): PermissionState = when {
        Build.VERSION.SDK_INT < minimumApi -> PermissionState.NOT_APPLICABLE
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
        else -> PermissionState.DENIED
    }

    @Suppress("DEPRECATION")
    private fun installerPackage(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun cpuDescriptor(): String? = try {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { line ->
                val name = line.substringBefore(':').trim().lowercase()
                name in setOf("hardware", "processor", "model name")
            }?.substringAfter(':')?.trim()?.take(240)?.ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }

    private fun String.nonBlank(fallback: String): String = trim().ifBlank { fallback }
}
