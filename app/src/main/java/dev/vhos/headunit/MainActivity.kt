package dev.vhos.headunit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.vhos.digitaltwin.DigitalTwinSnapshot
import dev.vhos.digitaltwin.Drivetrain
import dev.vhos.digitaltwin.EngineConfiguration
import dev.vhos.digitaltwin.HeadUnitInventory
import dev.vhos.digitaltwin.HealthSummary
import dev.vhos.digitaltwin.MileageSource
import dev.vhos.digitaltwin.ModificationState
import dev.vhos.digitaltwin.RearSuspension
import dev.vhos.digitaltwin.TriState
import dev.vhos.digitaltwin.VehicleProfile
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
import java.util.UUID

class MainActivity : Activity() {
    @Volatile private var database: EvidenceDatabase? = null
    @Volatile private var storeInitializationError: String? = null
    @Volatile private var activityDestroyed = false
    private lateinit var statusText: TextView
    private lateinit var obdCard: TextView
    private lateinit var acCard: TextView
    private lateinit var storageCard: TextView
    private lateinit var inventoryCard: TextView
    private lateinit var vehicleProfileCard: TextView
    private lateinit var healthMapCard: TextView
    private lateinit var discoveryCard: TextView
    private lateinit var releaseCard: TextView
    private lateinit var releaseHub: ReleaseHubManager
    private var pendingExport: ByteArray? = null
    private var pendingDigitalTwinExport: ByteArray? = null
    private val observer: (HeadUnitSnapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        releaseHub = ReleaseHubManager(this) { snapshot ->
            runOnUiThread { renderRelease(snapshot) }
        }
        setContentView(buildContent())
        HeadUnitRuntime.observe(observer)
        releaseHub.refresh()
        initializeEvidenceStore()
    }

    override fun onDestroy() {
        activityDestroyed = true
        HeadUnitRuntime.removeObserver(observer)
        releaseHub.close()
        super.onDestroy()
    }

    private fun initializeEvidenceStore() {
        storeInitializationError = null
        render(HeadUnitRuntime.snapshot())
        Thread {
            try {
                database = EvidenceDatabase.open(applicationContext)
                if (activityDestroyed) return@Thread
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    render(HeadUnitRuntime.snapshot())
                    refreshCounts()
                    captureHeadUnitInventory()
                    refreshDigitalTwin()
                    refreshDiscovery()
                }
            } catch (error: Exception) {
                storeInitializationError = error.message ?: error.javaClass.simpleName
                if (!activityDestroyed) runOnUiThread {
                    if (!activityDestroyed) render(HeadUnitRuntime.snapshot())
                }
            }
        }.start()
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
        controls.addView(controlRow(
            "Vehicle profile" to ::editVehicleProfile,
            "Refresh inventory" to ::captureHeadUnitInventory,
            "Export digital twin" to ::prepareDigitalTwinExport,
        ))
        root.addView(controls)

        val twinColumns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        inventoryCard = card(16f)
        vehicleProfileCard = card(16f)
        twinColumns.addView(inventoryCard, weightedCardParams())
        twinColumns.addView(vehicleProfileCard, weightedCardParams())
        root.addView(twinColumns)

        healthMapCard = card(15f).apply {
            text = getString(R.string.health_map_initializing)
            setTextColor(levelColor(IndicatorLevel.WAIT))
        }
        root.addView(healthMapCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })

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
            text = getString(R.string.can_discovery_wait)
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
        val store = evidenceStoreOrNotify() ?: return
        Thread {
            try {
                val records = store.recentPortableFrames()
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
            DIGITAL_TWIN_EXPORT_REQUEST -> writeDigitalTwinExport(uri)
        }
    }

    private fun captureHeadUnitInventory() {
        val store = evidenceStoreOrNotify() ?: return
        val inventory = try {
            HeadUnitInventoryCollector.capture(this)
        } catch (error: Exception) {
            showError(error)
            return
        }
        Thread {
            try {
                store.persistHeadUnitInventory(inventory)
                refreshDigitalTwin()
            } catch (error: Exception) {
                showError(error)
            }
        }.start()
    }

    private fun refreshDigitalTwin() {
        val store = evidenceStoreOrNotify() ?: return
        Thread {
            try {
                store.ensureInitialUnknownHealthMap()
                val snapshot = store.digitalTwinSnapshot()
                runOnUiThread { renderDigitalTwin(snapshot) }
            } catch (error: Exception) {
                runOnUiThread {
                    if (::healthMapCard.isInitialized) {
                        healthMapCard.text = getString(
                            R.string.health_map_unavailable_format,
                            error.message ?: error.javaClass.simpleName,
                        )
                        healthMapCard.setTextColor(levelColor(IndicatorLevel.BLOCKED))
                    }
                }
            }
        }.start()
    }

    private fun prepareDigitalTwinExport() {
        val store = evidenceStoreOrNotify() ?: return
        Thread {
            try {
                store.ensureInitialUnknownHealthMap()
                pendingDigitalTwinExport = store.exportDigitalTwin()
                runOnUiThread {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(
                                Intent.EXTRA_TITLE,
                                "vhos-digital-twin-${Instant.now().epochSecond}.json",
                            )
                        },
                        DIGITAL_TWIN_EXPORT_REQUEST,
                    )
                }
            } catch (error: Exception) {
                showError(error)
            }
        }.start()
    }

    private fun writeDigitalTwinExport(uri: Uri) {
        val bytes = pendingDigitalTwinExport ?: return
        Thread {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Android did not provide a digital-twin export stream.")
                pendingDigitalTwinExport = null
                showToast(
                    "Versioned digital twin exported • SHA-256 ${EvidenceBundles.sha256(bytes).take(12)}…"
                )
            } catch (error: Exception) {
                showError(error)
            }
        }.start()
    }

    private fun editVehicleProfile() {
        val store = evidenceStoreOrNotify() ?: return
        val current = try {
            store.latestVehicleProfile()
        } catch (error: Exception) {
            showError(error)
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun field(label: String, value: String? = null, numeric: Boolean = false): EditText =
            EditText(this).apply {
                hint = label
                setText(value.orEmpty())
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                form.addView(this)
            }
        fun <T> selector(values: List<T>, labels: List<String>, selected: T?): Spinner =
            Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    labels,
                )
                setSelection(values.indexOf(selected).coerceAtLeast(0))
                form.addView(this)
            }

        val vin = field("VIN (17 characters)", current?.vin)
        val mileage = field("Current odometer miles", current?.currentMileage?.toString(), numeric = true)
        val trim = field("Trim", current?.trim)
        val buildDate = field("Build date (YYYY-MM)", current?.buildDate)
        val tires = field("Tire configuration", current?.tireConfiguration)
        val modifications = field(
            "Modifications (comma or line separated)",
            current?.modifications?.joinToString(", "),
        ).apply { minLines = 2 }
        val engines = EngineConfiguration.entries
        val engine = selector(engines, engines.map { it.displayName }, current?.engine)
        val drivetrains = Drivetrain.entries
        val drivetrain = selector(drivetrains, drivetrains.map { it.displayName }, current?.drivetrain)
        val suspensions = RearSuspension.entries
        val suspension = selector(suspensions, suspensions.map { it.displayName }, current?.rearSuspension)
        val severeUseValues = TriState.entries
        val severeUse = selector(
            severeUseValues,
            listOf("Severe use: unknown", "Severe use: yes", "Severe use: no"),
            current?.severeUse,
        )
        val modificationStates = ModificationState.entries
        val modificationState = selector(
            modificationStates,
            listOf("Modifications: unknown", "Modifications: stock", "Modifications: modified"),
            current?.modificationState,
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("2005 Toyota 4Runner configuration")
            .setMessage("Each save appends a permanent revision. Unknown values remain unknown.")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save revision", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val mileageValue = mileage.text.toString().trim().takeIf(String::isNotEmpty)?.toLong()
                    val now = Instant.now().toString()
                    val selectedEngine = engines[engine.selectedItemPosition]
                    val profile = VehicleProfile(
                        revisionId = UUID.randomUUID().toString(),
                        supersedesRevisionId = current?.revisionId,
                        createdAt = now,
                        vin = vin.text.toString().trim().uppercase(Locale.US).takeIf(String::isNotEmpty),
                        engine = selectedEngine,
                        timingDrive = selectedEngine.timingDrive,
                        drivetrain = drivetrains[drivetrain.selectedItemPosition],
                        rearSuspension = suspensions[suspension.selectedItemPosition],
                        trim = trim.text.toString().trim().takeIf(String::isNotEmpty),
                        buildDate = buildDate.text.toString().trim().takeIf(String::isNotEmpty),
                        tireConfiguration = tires.text.toString().trim().takeIf(String::isNotEmpty),
                        severeUse = severeUseValues[severeUse.selectedItemPosition],
                        modificationState = modificationStates[modificationState.selectedItemPosition],
                        modifications = modifications.text.toString()
                            .split(',', '\n')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .distinct(),
                        currentMileage = mileageValue,
                        mileageObservedAt = mileageValue?.let { now },
                        mileageSource = if (mileageValue == null) {
                            MileageSource.UNKNOWN
                        } else {
                            MileageSource.MANUAL_ODOMETER
                        },
                    ).validate()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    Thread {
                        try {
                            store.appendVehicleProfile(profile)
                            refreshDigitalTwin()
                            runOnUiThread {
                                dialog.dismiss()
                                Toast.makeText(
                                    this,
                                    "Vehicle profile revision saved; all system health remains evidence-gated.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                Toast.makeText(
                                    this,
                                    error.message ?: error.javaClass.simpleName,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }.start()
                } catch (error: Exception) {
                    showError(error)
                }
            }
        }
        dialog.show()
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
        val store = evidenceStoreOrNotify() ?: return
        Thread {
            try {
                val bundle = contentResolver.openInputStream(uri)?.use(EvidenceBundles::read)
                    ?: throw IllegalStateException("Android did not provide an import stream.")
                val inserted = store.importBundle(bundle)
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
        val store = database ?: return
        val counts = store.counts()
        HeadUnitRuntime.updateCounts(counts.logicalFrames, counts.canObservations)
    }

    private fun refreshDiscovery() {
        val store = evidenceStoreOrNotify() ?: return
        if (::discoveryCard.isInitialized) {
            discoveryCard.text = getString(R.string.can_discovery_analyzing)
            discoveryCard.setTextColor(levelColor(IndicatorLevel.ACTIVE))
        }
        Thread {
            try {
                val total = store.counts().canObservations
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
                val persisted = store.recentCanObservations(DISCOVERY_RECORD_LIMIT)
                val report = CanDiscoveryAnalyzer.analyze(
                    persisted.map { DiscoveryObservation(it.sourceId, it.observation) }
                )
                runOnUiThread {
                    discoveryCard.text = discoveryText(report, total)
                    discoveryCard.setTextColor(levelColor(IndicatorLevel.CHECK))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    discoveryCard.text = getString(
                        R.string.can_discovery_unavailable_format,
                        error.message ?: error.javaClass.simpleName,
                    )
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
        val store = database
        val storeError = storeInitializationError
        storageCard.text = when {
            storeError != null -> buildString {
                appendLine("LOCAL EVIDENCE  LOCKED")
                appendLine("Encrypted store failed closed; no evidence was modified.")
                append(storeError)
            }
            store == null -> "LOCAL EVIDENCE  SECURING\nOpening Android Keystore envelope and verifying SQLCipher pages…"
            else -> buildString {
                val security = store.securityStatus
                appendLine("LOCAL EVIDENCE  ${snapshot.storedLogicalFrames} FRAMES")
                appendLine("CAN observations: ${snapshot.storedCanObservations}")
                appendLine("Database: append-only SQLCipher / WAL • schema v2")
                appendLine("Encryption: ${security.cipherVersion} • KEYSTORE ENVELOPE v${security.keyEnvelopeVersion}")
                appendLine("Key: ${security.keyProtection}")
                appendLine("Migration: ${security.migrationState.displayName.uppercase(Locale.US)}")
                appendLine("Export: SHA-256 manifest + NDJSON")
                append("Last import/export: ${if (snapshot.lastImportAtEpochMs != null || snapshot.lastExportAtEpochMs != null) "RECORDED" else "NONE"}")
            }
        }
        storageCard.setTextColor(
            levelColor(
                when {
                    storeError != null -> IndicatorLevel.BLOCKED
                    store == null -> IndicatorLevel.ACTIVE
                    else -> IndicatorLevel.PASS
                }
            )
        )
    }

    private fun renderDigitalTwin(snapshot: DigitalTwinSnapshot) {
        val inventory = snapshot.headUnitInventory
        inventoryCard.text = if (inventory == null) {
            "HEAD-UNIT INVENTORY  UNAVAILABLE\nNo platform inventory has been captured."
        } else {
            inventoryText(inventory)
        }
        inventoryCard.setTextColor(
            levelColor(if (inventory == null) IndicatorLevel.WAIT else IndicatorLevel.PASS)
        )

        val profile = snapshot.vehicleProfile
        vehicleProfileCard.text = profileText(profile)
        vehicleProfileCard.setTextColor(
            levelColor(
                when {
                    profile == null -> IndicatorLevel.WAIT
                    profile.scheduleReady -> IndicatorLevel.PASS
                    else -> IndicatorLevel.CHECK
                }
            )
        )

        val summary = HealthSummary.from(snapshot.healthAssessments)
        healthMapCard.text = buildString {
            appendLine(
                "WHOLE-VEHICLE HEALTH MAP  ${summary.establishedSystems}/${summary.totalSystems} ESTABLISHED"
            )
            appendLine(
                "Unknown ${summary.unknownSystems} • directly measured ${summary.byBasis[dev.vhos.digitaltwin.EvidenceBasis.DIRECT_MEASUREMENT]} • " +
                    "calculated ${summary.byBasis[dev.vhos.digitaltwin.EvidenceBasis.CALCULATED]} • " +
                    "schedule ${summary.byBasis[dev.vhos.digitaltwin.EvidenceBasis.SCHEDULE]} • " +
                    "inspection ${summary.byBasis[dev.vhos.digitaltwin.EvidenceBasis.INSPECTION]} • " +
                    "inferred ${summary.byBasis[dev.vhos.digitaltwin.EvidenceBasis.INFERRED]}"
            )
            appendLine("No DTCs is not treated as proof of health.")
            snapshot.healthAssessments.forEach { assessment ->
                appendLine(
                    "${assessment.systemId.displayName}: ${assessment.state.name} • ${assessment.basis.name}"
                )
            }
        }.trimEnd()
        healthMapCard.setTextColor(
            levelColor(if (summary.unknownSystems > 0) IndicatorLevel.WAIT else IndicatorLevel.CHECK)
        )
    }

    private fun inventoryText(inventory: HeadUnitInventory): String = buildString {
        appendLine("HEAD-UNIT INVENTORY  CAPTURED")
        appendLine("${inventory.hardware.manufacturer} ${inventory.hardware.model}")
        appendLine(
            "Android ${inventory.android.release} / API ${inventory.android.apiLevel} • " +
                "patch ${inventory.android.securityPatch.ifBlank { "unknown" }}"
        )
        appendLine(
            "CPU ${inventory.hardware.cpuDescriptor ?: inventory.hardware.hardware} • " +
                "${inventory.hardware.logicalCpuCount} logical • ${inventory.hardware.supportedAbis.joinToString()}"
        )
        appendLine(
            "RAM ${formatBytes(inventory.hardware.totalRamBytes)} total / " +
                "${formatBytes(inventory.hardware.availableRamBytes)} available"
        )
        appendLine(
            "Storage ${formatBytes(inventory.hardware.freeInternalStorageBytes)} free / " +
                "${formatBytes(inventory.hardware.totalInternalStorageBytes)}"
        )
        appendLine(
            "Display ${inventory.display.widthPixels}×${inventory.display.heightPixels} • " +
                "${inventory.display.densityDpi} dpi • ${inventory.display.widthDp}×${inventory.display.heightDp} dp"
        )
        appendLine(
            "BLE ${if (inventory.capabilities.bleFeature) "PRESENT" else "MISSING"} • " +
                "scan ${inventory.capabilities.bluetoothScanPermission} • " +
                "connect ${inventory.capabilities.bluetoothConnectPermission}"
        )
        append(
            "APK install ${inventory.capabilities.unknownSourceInstall} • " +
                "battery exemption ${if (inventory.capabilities.batteryOptimizationExempt) "YES" else "NO"}"
        )
    }

    private fun profileText(profile: VehicleProfile?): String = if (profile == null) {
        "VEHICLE PROFILE  CONFIGURATION REQUIRED\n" +
            "2005 Toyota 4Runner • VIN, engine, drivetrain, suspension, trim/build date, tires, use, modifications, and mileage remain unknown.\n" +
            "No variant-specific service schedule is active."
    } else buildString {
        appendLine("VEHICLE PROFILE  ${if (profile.scheduleReady) "SCHEDULE READY" else "INCOMPLETE"}")
        appendLine("2005 Toyota 4Runner • VIN ${profile.vin ?: "UNKNOWN"}")
        appendLine("Vehicle pack ${profile.vehiclePackId} @ ${profile.vehiclePackVersion}")
        appendLine("${profile.engine.displayName} • ${profile.timingDrive.displayName}")
        appendLine("${profile.drivetrain.displayName} • ${profile.rearSuspension.displayName}")
        appendLine("Trim ${profile.trim ?: "UNKNOWN"} • build ${profile.buildDate ?: "UNKNOWN"}")
        appendLine("Tires ${profile.tireConfiguration ?: "UNKNOWN"} • severe use ${profile.severeUse}")
        appendLine(
            "Mileage ${profile.currentMileage?.let { String.format(Locale.US, "%,d mi", it) } ?: "UNKNOWN"}"
        )
        appendLine(
            "Modifications ${profile.modificationState}: " +
                profile.modifications.ifEmpty { listOf("NONE RECORDED") }.joinToString()
        )
        if (profile.scheduleReady) {
            append("Variant guard active: ${profile.timingDrive.displayName} rules only.")
        } else {
            append("Still required: ${profile.scheduleReadinessIssues().joinToString()}.")
        }
    }

    private fun deviceText(device: DeviceSnapshot): String = buildString {
        appendLine("${device.role.displayName.uppercase()}  ${device.phase.displayName.uppercase()}")
        appendLine(device.detail)
        appendLine("Device: ${device.deviceName ?: "—"}  RSSI: ${device.rssiDbm?.let { "$it dBm" } ?: "—"}")
        appendLine("Source: ${device.sourceId ?: "unverified"}  Firmware: ${device.firmwareVersion ?: "—"}")
        appendLine("VHOS frames: ${device.logicalFrames}  persisted: ${device.persistedFrames}")
        appendLine("Vehicle frames: ${device.vehicleFrames}  bitrate: ${device.bitrateBps ?: "—"}")
        appendLine("CRC: ${device.crcFailures}  protocol: ${device.protocolFailures}  bus errors/off: ${device.busErrors}/${device.busOffEvents}")
        if (device.role == dev.vhos.model.DeviceRole.OBD_CAN) {
            appendLine(
                "J1979 ECUs: ${device.j1979EcuCount}  enumeration: " +
                    when (device.j1979EnumerationComplete) {
                        true -> "COMPLETE"
                        false -> "INCOMPLETE"
                        null -> "NO EVIDENCE"
                    } + "  supported PIDs: ${device.j1979SupportedPidCount}"
            )
            if (device.standardObdReadings.isEmpty()) {
                appendLine("Standard OBD: unavailable until supported-PID evidence is complete.")
            } else {
                appendLine("STANDARD READ-ONLY OBD")
                device.standardObdReadings.take(8).forEach { reading ->
                    appendLine(
                        "${reading.name}: ${decimal(reading.value, 2)} ${reading.unit} " +
                            "(${reading.ecuAddress}/PID ${String.format(Locale.US, "%02X", reading.pid)})"
                    )
                }
            }
        }
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

    private fun evidenceStoreOrNotify(): EvidenceDatabase? {
        val store = database
        if (store == null) {
            showToast(
                storeInitializationError?.let { "Encrypted evidence store unavailable: $it" }
                    ?: "Encrypted evidence store is still being verified."
            )
        }
        return store
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
    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) String.format(Locale.US, "%.2f GiB", gib)
        else String.format(Locale.US, "%.0f MiB", bytes.toDouble() / (1024.0 * 1024.0))
    }
    private fun showToast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showError(error: Exception) = showToast(error.message ?: error.javaClass.simpleName)

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val EXPORT_REQUEST = 1002
        private const val IMPORT_REQUEST = 1003
        private const val DIGITAL_TWIN_EXPORT_REQUEST = 1004
        private const val DISCOVERY_RECORD_LIMIT = 100_000
    }
}
