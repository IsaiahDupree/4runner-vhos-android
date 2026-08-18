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
import dev.vhos.discovery.CanDiscoveryAnalyzer
import dev.vhos.discovery.CanDiscoveryReport
import dev.vhos.discovery.DiscoveryObservation
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.HeadUnitSnapshot
import dev.vhos.model.IndicatorLevel
import dev.vhos.store.EvidenceDatabase
import dev.vhos.sync.BundleCreator
import dev.vhos.sync.EvidenceBundles
import java.time.Instant
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var statusText: TextView
    private lateinit var obdCard: TextView
    private lateinit var acCard: TextView
    private lateinit var storageCard: TextView
    private lateinit var discoveryCard: TextView
    private lateinit var releaseCard: TextView
    private lateinit var releaseHub: ReleaseHubManager
    private var pendingExport: ByteArray? = null
    private val observer: (HeadUnitSnapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = EvidenceDatabase(this)
        releaseHub = ReleaseHubManager(this) { snapshot ->
            runOnUiThread { renderRelease(snapshot) }
        }
        setContentView(buildContent())
        HeadUnitRuntime.observe(observer)
        refreshCounts()
        refreshDiscovery()
        releaseHub.refresh()
    }

    override fun onDestroy() {
        HeadUnitRuntime.removeObserver(observer)
        releaseHub.close()
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
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        controls.addView(controlRow(
            "Connect / Reacquire" to ::startVehicleSession,
            "Stop" to { sendServiceAction(VehicleSessionService.ACTION_STOP) },
            "Release for iPhone" to { sendServiceAction(VehicleSessionService.ACTION_RELEASE) },
        ))
        controls.addView(controlRow(
            "Bluetooth settings" to ::openBluetoothSettings,
            "Permissions" to ::openAppSettings,
            "Export" to ::prepareExport,
            "Import" to ::chooseImport,
        ))
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

        discoveryCard = card(16f).apply {
            text = "CAN DISCOVERY  WAIT\nNo persisted CAN observations have been analyzed yet."
            setTextColor(levelColor(IndicatorLevel.WAIT))
        }
        root.addView(discoveryCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })
        root.addView(controlRow(
            "Analyze saved CAN" to ::refreshDiscovery,
        ))

        releaseCard = card(16f)
        root.addView(releaseCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })
        val releaseControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        releaseControls.addView(button("Refresh releases") { releaseHub.refresh() })
        releaseControls.addView(button("Download Android APK") { releaseHub.stageAndroidUpdate() })
        releaseControls.addView(button("Install verified APK") {
            try { releaseHub.requestInstall(this@MainActivity) } catch (error: Exception) { showError(error) }
        })
        root.addView(releaseControls)
        renderRelease(releaseHub.current())

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
                refreshDiscovery()
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

    private fun refreshDiscovery() {
        if (::discoveryCard.isInitialized) {
            discoveryCard.text = "CAN DISCOVERY  ANALYZING\nReading append-only observations…"
            discoveryCard.setTextColor(levelColor(IndicatorLevel.ACTIVE))
        }
        Thread {
            try {
                val total = database.counts().canObservations
                if (total == 0L) {
                    runOnUiThread {
                        discoveryCard.text = buildString {
                            appendLine("CAN DISCOVERY  NO EVIDENCE")
                            appendLine("Import a verified iPhone .vhossync bundle or capture frames directly.")
                            append("No vehicle value is inferred while raw evidence is absent.")
                        }
                        discoveryCard.setTextColor(levelColor(IndicatorLevel.WAIT))
                    }
                    return@Thread
                }
                val persisted = database.recentCanObservations(DISCOVERY_RECORD_LIMIT)
                val report = CanDiscoveryAnalyzer.analyze(
                    persisted.map { DiscoveryObservation(it.sourceId, it.observation) }
                )
                runOnUiThread {
                    discoveryCard.text = discoveryText(report, total)
                    discoveryCard.setTextColor(levelColor(IndicatorLevel.CHECK))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    discoveryCard.text = "CAN DISCOVERY  UNAVAILABLE\n${error.message ?: error.javaClass.simpleName}"
                    discoveryCard.setTextColor(levelColor(IndicatorLevel.BLOCKED))
                }
            }
        }.start()
    }

    private fun discoveryText(report: CanDiscoveryReport, totalRows: Long): String = buildString {
        val acquisition = report.acquisition
        appendLine("CAN DISCOVERY  CANDIDATES ONLY • MODEL ${report.contractVersion}")
        appendLine("PROVEN ACQUISITION")
        appendLine(
            "${acquisition.records} analyzed of $totalRows retained • " +
                "${acquisition.sessions} sessions • ${acquisition.uniqueIdentifiers} identifiers"
        )
        appendLine(
            "${acquisition.bitratesBps.joinToString { "${it / 1_000} kbit/s" }} • " +
                "listen-only ${acquisition.listenOnlyRecords}/${acquisition.records} • " +
                "11-bit ${acquisition.standardIdentifierRecords}/${acquisition.records} • " +
                "RTR ${acquisition.remoteRequestRecords}"
        )
        appendLine(
            "Observed-rate estimate ${decimal(acquisition.estimatedObservedRateFps)} frames/s • " +
                "retained ${decimal(acquisition.retainedRecordRateFps)}/s • " +
                "sampled coverage ${percent(acquisition.sequenceCoverage)}"
        )
        appendLine()
        appendLine("RAW ACTIVITY — NO ASSIGNED VEHICLE MEANINGS")
        report.identifierActivity.take(6).forEach { item ->
            val word = item.firstBigEndianWord?.let {
                " • raw BE16[0] ${it.minimum}–${it.maximum}"
            }.orEmpty()
            appendLine(
                "${item.identifierHex} • ${item.records} records • ${item.uniquePayloads} payloads • " +
                    "dynamic bytes ${item.dynamicBytePositions.joinToString(prefix = "[", postfix = "]")}$word"
            )
        }
        val checksums = report.identifierActivity.filter { it.checksum.candidate }
        appendLine()
        appendLine("INTEGRITY CANDIDATES")
        if (checksums.isEmpty()) appendLine("No additive-checksum family met the candidate gate.")
        else appendLine(
            "${checksums.size} IDs • ${checksums.sumOf { it.checksum.matches }}/" +
                "${checksums.sumOf { it.checksum.checked }} candidate checksum matches"
        )
        report.repeatedChannels.take(2).forEach { item ->
            appendLine(
                "${identifierHex(item.identifier)} bytes ${item.bytePositions.joinToString()} agree across " +
                    "${item.recordsCompared} retained records (candidate only)"
            )
        }
        if (report.rawWordRelationships.isNotEmpty()) {
            appendLine()
            appendLine("RAW RELATIONSHIPS")
            report.rawWordRelationships.take(3).forEach { relation ->
                appendLine(
                    "${identifierHex(relation.leftIdentifier)}↔${identifierHex(relation.rightIdentifier)} " +
                        "BE16 correlation ${decimal(relation.pearsonCorrelation, 3)} • " +
                        "${relation.pairedSamples} paired samples"
                )
            }
        }
        appendLine()
        append("INTERPRETATION LOCK • RPM, speed, gear, throttle, steering, brake, and health thresholds remain unavailable until an independent reference capture validates exact bytes, scaling, applicability, and lineage.")
    }

    private fun render(snapshot: HeadUnitSnapshot) {
        statusText.text = getString(
            R.string.system_status_format,
            if (snapshot.running) "ACTIVE" else "IDLE",
            when {
                snapshot.obd.phase == dev.vhos.model.ConnectionPhase.RELEASED_FOR_EXTERNAL_CLIENT -> "IPHONE"
                snapshot.running -> "ANDROID"
                else -> "NONE"
            },
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
        appendLine("CRC: ${device.crcFailures}  protocol: ${device.protocolFailures}  bus errors/off: ${device.busErrors}/${device.busOffEvents}")
        if (device.transportErrorName != null || device.platformErrorCode != null) {
            appendLine(
                "Android transport: ${device.transportErrorName ?: "UNKNOWN"}" +
                    device.platformErrorCode?.let { " ($it)" }.orEmpty()
            )
        }
        if (device.recoveryAttempt > 0) {
            append("Recovery: attempt ${device.recoveryAttempt}")
            device.nextRetryAtEpochMs?.let { append("  next: ${Instant.ofEpochMilli(it)}") }
        } else {
            append("Recovery: idle")
        }
    }

    private fun renderRelease(snapshot: ReleaseHubSnapshot) {
        releaseCard.text = buildString {
            appendLine("SIGNED RELEASE HUB  ${if (snapshot.busy) "ACTIVE" else "READY"}")
            appendLine(snapshot.status)
            snapshot.catalog?.artifacts?.forEach { artifact ->
                appendLine("${artifact.target}: ${artifact.version} • ${artifact.readiness}")
            }
            if (snapshot.stagedApk != null) append("APK: HASH + PACKAGE + CERTIFICATE VERIFIED")
            else append("APK: NOT STAGED")
        }
        releaseCard.setTextColor(
            levelColor(
                when {
                    snapshot.busy -> IndicatorLevel.ACTIVE
                    snapshot.catalog == null -> IndicatorLevel.WAIT
                    snapshot.stagedApk != null -> IndicatorLevel.PASS
                    else -> IndicatorLevel.CHECK
                }
            )
        )
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

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
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

    private fun controlRow(vararg controls: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            controls.forEach { (label, action) -> addView(button(label, action)) }
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
    private fun decimal(value: Double, digits: Int = 1): String =
        String.format(Locale.US, "%.${digits}f", value)
    private fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value * 100.0)
    private fun identifierHex(identifier: UInt): String =
        String.format(Locale.US, "0x%03X", identifier.toInt())
    private fun showToast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showError(error: Exception) = showToast(error.message ?: error.javaClass.simpleName)

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val EXPORT_REQUEST = 1002
        private const val IMPORT_REQUEST = 1003
        private const val DISCOVERY_RECORD_LIMIT = 100_000
    }
}
