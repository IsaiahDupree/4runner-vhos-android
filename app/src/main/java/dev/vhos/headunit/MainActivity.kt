package dev.vhos.headunit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.HeadUnitSnapshot
import dev.vhos.model.IndicatorLevel
import dev.vhos.store.EvidenceDatabase
import dev.vhos.sync.BundleCreator
import dev.vhos.sync.EvidenceBundles
import java.time.Instant

class MainActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var statusText: TextView
    private lateinit var obdCard: TextView
    private lateinit var acCard: TextView
    private lateinit var storageCard: TextView
    private var pendingExport: ByteArray? = null
    private val observer: (HeadUnitSnapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = EvidenceDatabase(this)
        setContentView(buildContent())
        HeadUnitRuntime.observe(observer)
        refreshCounts()
    }

    override fun onDestroy() {
        HeadUnitRuntime.removeObserver(observer)
        database.close()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(28))
            setBackgroundColor(getColor(R.color.vhos_background))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.screen_title)
            textSize = 28f
            setTextColor(getColor(R.color.vhos_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.screen_subtitle)
            textSize = 15f
            setTextColor(getColor(R.color.vhos_muted))
            setPadding(0, dp(3), 0, dp(16))
        })
        statusText = card(18f)
        root.addView(statusText)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        controls.addView(button("Start / Reacquire") { startVehicleSession() })
        controls.addView(button("Stop") { sendServiceAction(VehicleSessionService.ACTION_STOP) })
        controls.addView(button("Release for iPhone") { sendServiceAction(VehicleSessionService.ACTION_RELEASE) })
        controls.addView(button("Export") { prepareExport() })
        controls.addView(button("Import") { chooseImport() })
        controls.addView(button("Permissions") { openAppSettings() })
        root.addView(controls)

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        obdCard = card(17f)
        acCard = card(17f)
        storageCard = card(17f)
        columns.addView(obdCard, weightedCardParams())
        columns.addView(acCard, weightedCardParams())
        columns.addView(storageCard, weightedCardParams())
        root.addView(columns)

        root.addView(TextView(this).apply {
            text = getString(R.string.transport_proof_disclaimer)
            textSize = 14f
            setTextColor(getColor(R.color.vhos_muted))
            setPadding(0, dp(16), 0, 0)
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun startVehicleSession() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        val intent = Intent(this, VehicleSessionService::class.java).setAction(VehicleSessionService.ACTION_START)
        startForegroundService(intent)
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, VehicleSessionService::class.java).setAction(action))
    }

    private fun prepareExport() {
        Thread {
            try {
                val records = database.recentPortableFrames()
                if (records.isEmpty()) throw IllegalStateException("No validated logical frames are stored yet.")
                pendingExport = EvidenceBundles.toByteArray(
                    records = records,
                    creator = BundleCreator(
                        platform = "ANDROID",
                        applicationId = BuildConfig.APPLICATION_ID,
                        applicationVersion = BuildConfig.VERSION_NAME,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    ),
                )
                runOnUiThread {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "vhos-evidence-${Instant.now().epochSecond}.vhossync")
                        },
                        EXPORT_REQUEST,
                    )
                }
            } catch (error: Exception) {
                showError(error)
            }
        }.start()
    }

    private fun chooseImport() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            IMPORT_REQUEST,
        )
    }

    @Deprecated("Storage Access Framework result handling remains compatible with API 26.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            EXPORT_REQUEST -> writeExport(uri)
            IMPORT_REQUEST -> readImport(uri)
        }
    }

    private fun writeExport(uri: Uri) {
        val bytes = pendingExport ?: return
        Thread {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Android did not provide an export stream.")
                pendingExport = null
                HeadUnitRuntime.markExport(System.currentTimeMillis())
                showToast("Checksummed VHOS evidence bundle exported.")
            } catch (error: Exception) {
                showError(error)
            }
        }.start()
    }

    private fun readImport(uri: Uri) {
        Thread {
            try {
                val bundle = contentResolver.openInputStream(uri)?.use(EvidenceBundles::read)
                    ?: throw IllegalStateException("Android did not provide an import stream.")
                val inserted = database.importBundle(bundle)
                HeadUnitRuntime.markImport(System.currentTimeMillis())
                refreshCounts()
                showToast("Verified ${bundle.records.size} records; appended $inserted new frames.")
            } catch (error: Exception) {
                showError(error)
            }
        }.start()
    }

    private fun refreshCounts() {
        val counts = database.counts()
        HeadUnitRuntime.updateCounts(counts.logicalFrames, counts.canObservations)
    }

    private fun render(snapshot: HeadUnitSnapshot) {
        statusText.text = getString(
            R.string.system_status_format,
            if (snapshot.running) "ACTIVE" else "IDLE",
            snapshot.status,
        )
        statusText.setTextColor(levelColor(if (snapshot.running) IndicatorLevel.ACTIVE else IndicatorLevel.WAIT))
        obdCard.text = deviceText(snapshot.obd)
        obdCard.setTextColor(levelColor(snapshot.obd.level))
        acCard.text = deviceText(snapshot.ac)
        acCard.setTextColor(levelColor(snapshot.ac.level))
        storageCard.text = buildString {
            appendLine("LOCAL EVIDENCE  ${snapshot.storedLogicalFrames} FRAMES")
            appendLine("CAN observations: ${snapshot.storedCanObservations}")
            appendLine("Database: append-only SQLite / WAL")
            appendLine("Export: SHA-256 manifest + NDJSON")
            append("Last import/export: ${if (snapshot.lastImportAtEpochMs != null || snapshot.lastExportAtEpochMs != null) "RECORDED" else "NONE"}")
        }
        storageCard.setTextColor(levelColor(IndicatorLevel.PASS))
    }

    private fun deviceText(device: DeviceSnapshot): String = buildString {
        appendLine("${device.role.displayName.uppercase()}  ${device.phase.displayName.uppercase()}")
        appendLine(device.detail)
        appendLine("Device: ${device.deviceName ?: "—"}  RSSI: ${device.rssiDbm?.let { "$it dBm" } ?: "—"}")
        appendLine("Source: ${device.sourceId ?: "unverified"}  Firmware: ${device.firmwareVersion ?: "—"}")
        appendLine("VHOS frames: ${device.logicalFrames}  persisted: ${device.persistedFrames}")
        appendLine("Vehicle frames: ${device.vehicleFrames}  bitrate: ${device.bitrateBps ?: "—"}")
        append("CRC: ${device.crcFailures}  protocol: ${device.protocolFailures}  bus errors/off: ${device.busErrors}/${device.busOffEvents}")
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startVehicleSession()
        } else if (requestCode == PERMISSION_REQUEST) {
            Toast.makeText(this, "Nearby-device permission is required to discover VHOS hardware.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun card(size: Float): TextView = TextView(this).apply {
        textSize = size
        setPadding(dp(18), dp(16), dp(18), dp(16))
        setBackgroundColor(getColor(R.color.vhos_surface))
        setTextColor(getColor(R.color.vhos_text))
        setLineSpacing(0f, 1.12f)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) }
    }

    private fun weightedCardParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(10)
    }

    private fun levelColor(level: IndicatorLevel): Int = getColor(
        when (level) {
            IndicatorLevel.PASS -> R.color.vhos_pass
            IndicatorLevel.ACTIVE -> R.color.vhos_accent
            IndicatorLevel.CHECK -> R.color.vhos_check
            IndicatorLevel.BLOCKED -> R.color.vhos_blocked
            IndicatorLevel.WAIT -> R.color.vhos_muted
        }
    )
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun showToast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showError(error: Exception) = showToast(error.message ?: error.javaClass.simpleName)

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val EXPORT_REQUEST = 1002
        private const val IMPORT_REQUEST = 1003
    }
}
