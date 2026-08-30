package dev.vhos.headunit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.vhos.maintenance.CustomValueType
import dev.vhos.maintenance.DistanceUnit
import dev.vhos.maintenance.AttachmentAvailability
import dev.vhos.maintenance.AttachmentMetadata
import dev.vhos.maintenance.LineItemType
import dev.vhos.maintenance.MaintenanceArchiveCodec
import dev.vhos.maintenance.MaintenanceAuditEvent
import dev.vhos.maintenance.MaintenanceComponentRef
import dev.vhos.maintenance.MaintenanceCompletionClaim
import dev.vhos.maintenance.MaintenanceCompletionTrust
import dev.vhos.maintenance.MaintenanceEventType
import dev.vhos.maintenance.MaintenanceIds
import dev.vhos.maintenance.MaintenanceMeasurement
import dev.vhos.maintenance.MaintenanceOccurrencePrecision
import dev.vhos.maintenance.MaintenanceProvider
import dev.vhos.maintenance.MaintenanceRecordRevision
import dev.vhos.maintenance.MaintenanceRecordState
import dev.vhos.maintenance.MaintenanceRequirementAuthority
import dev.vhos.maintenance.MaintenanceRequirementRevision
import dev.vhos.maintenance.MaintenanceSearchQuery
import dev.vhos.maintenance.MaintenanceSystemRef
import dev.vhos.maintenance.MeasurementValueType
import dev.vhos.maintenance.Money
import dev.vhos.maintenance.OdometerReading
import dev.vhos.maintenance.PartFluidLineItem
import dev.vhos.maintenance.TypedCustomFieldValue
import dev.vhos.maintenance.VehicleAsset
import dev.vhos.maintenance.VehicleComponentRevision
import dev.vhos.maintenance.VehicleComponentState
import dev.vhos.maintenance.VoidMaintenanceRecordRequest
import dev.vhos.maintenance.WarrantyInfo
import dev.vhos.store.EvidenceDatabase
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Vehicle-agnostic, append-only maintenance Garage. Android owns the canonical encrypted ledger;
 * edits append revisions and voiding preserves history. No schedule or vehicle health claim is
 * inferred from the absence of records.
 */
@SuppressLint("SetTextI18n")
class MaintenanceActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    @Volatile private var generation = 0
    @Volatile private var database: EvidenceDatabase? = null

    private lateinit var status: TextView
    private lateinit var vehicleSpinner: Spinner
    private lateinit var queryInput: EditText
    private lateinit var typeFilter: Spinner
    private lateinit var includeVoided: CheckBox
    private lateinit var recordList: LinearLayout
    private lateinit var detail: TextView
    private lateinit var history: TextView
    private lateinit var amendButton: Button
    private lateinit var attachButton: Button
    private lateinit var voidButton: Button

    private var vehicles: List<VehicleAsset> = emptyList()
    private var components: List<VehicleComponentRevision> = emptyList()
    private var requirements: List<MaintenanceRequirementRevision> = emptyList()
    private var records: List<MaintenanceRecordRevision> = emptyList()
    private var selectedVehicleId: String? = null
    private var selectedRecordId: String? = null
    private var suppressVehicleCallback = false
    private var pendingAttachmentTarget: PendingAttachmentTarget? = null
    private var pendingArchiveVehicleId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingVehicleId = savedInstanceState?.getString(STATE_ATTACHMENT_VEHICLE_ID)
        val pendingRecordId = savedInstanceState?.getString(STATE_ATTACHMENT_RECORD_ID)
        val pendingRevisionId = savedInstanceState?.getString(STATE_ATTACHMENT_REVISION_ID)
        if (pendingVehicleId != null && pendingRecordId != null && pendingRevisionId != null) {
            pendingAttachmentTarget = PendingAttachmentTarget(
                pendingVehicleId,
                pendingRecordId,
                pendingRevisionId,
            )
        }
        pendingArchiveVehicleId = savedInstanceState?.getString(STATE_ARCHIVE_VEHICLE_ID)
        setContentView(buildRoot())
        refreshVehicles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAttachmentTarget?.let { target ->
            outState.putString(STATE_ATTACHMENT_VEHICLE_ID, target.vehicleId)
            outState.putString(STATE_ATTACHMENT_RECORD_ID, target.recordId)
            outState.putString(STATE_ATTACHMENT_REVISION_ID, target.revisionId)
        }
        pendingArchiveVehicleId?.let { outState.putString(STATE_ARCHIVE_VEHICLE_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Storage Access Framework result handling remains compatible with API 26.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            when (requestCode) {
                ATTACHMENT_REQUEST -> pendingAttachmentTarget = null
                MAINTENANCE_ARCHIVE_EXPORT_REQUEST -> pendingArchiveVehicleId = null
            }
            return
        }
        val uri = data?.data ?: run {
            pendingAttachmentTarget = null
            pendingArchiveVehicleId = null
            return
        }
        when (requestCode) {
            ATTACHMENT_REQUEST -> ingestMaintenanceAttachment(uri)
            MAINTENANCE_ARCHIVE_EXPORT_REQUEST -> writeMaintenanceArchive(uri)
        }
    }

    private fun chooseMaintenanceAttachment() {
        val record = currentRecord()
        if (record == null || record.state != MaintenanceRecordState.ACTIVE) {
            toast("Select an active maintenance record before attaching evidence.")
            return
        }
        pendingAttachmentTarget = PendingAttachmentTarget(
            vehicleId = record.vehicleId,
            recordId = record.recordId,
            revisionId = record.revisionId,
        )
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("image/*", "application/pdf", "text/plain", "application/octet-stream"),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            ATTACHMENT_REQUEST,
        )
    }

    private fun ingestMaintenanceAttachment(uri: Uri) {
        val target = pendingAttachmentTarget.also { pendingAttachmentTarget = null }
        if (target == null) {
            showOperationFailure("Attachment could not be stored", "The selected record context expired.")
            return
        }
        val displayName = MaintenanceDocumentTransfer.boundedDisplayName(
            providerValue = runCatching { documentDisplayName(uri) }.getOrNull(),
            fallback = "maintenance-document-${Instant.now().epochSecond}",
        )
        val mediaType = MaintenanceDocumentTransfer.canonicalMediaType(
            runCatching { contentResolver.getType(uri) }.getOrNull(),
        )
        statusLine("Encrypting $displayName and appending a record revision…", R.color.vhos_accent)
        worker.execute {
            var bytes: ByteArray? = null
            try {
                val db = database ?: EvidenceDatabase.open(applicationContext).also { database = it }
                bytes = contentResolver.openInputStream(uri)?.use {
                    MaintenanceDocumentTransfer.readBounded(it)
                }
                    ?: throw IllegalStateException("Android did not provide a readable document stream.")
                val stored = db.storeMaintenanceAttachment(requireNotNull(bytes), mediaType)
                val current = db.currentMaintenanceRecords(target.vehicleId, includeVoided = true)
                    .firstOrNull { it.recordId == target.recordId }
                    ?: throw IllegalStateException("The selected maintenance record no longer exists.")
                require(current.state == MaintenanceRecordState.ACTIVE) {
                    "The selected maintenance record is now voided and cannot accept attachments."
                }
                require(current.revisionId == target.revisionId) {
                    "The maintenance record changed while the document picker was open. Select it and attach again."
                }
                val metadata = AttachmentMetadata(
                    attachmentId = MaintenanceIds.attachment(),
                    displayName = displayName,
                    mediaType = stored.mediaType,
                    byteCount = stored.byteCount,
                    sha256 = stored.sha256,
                    storageKey = "sqlcipher:sha256/${stored.sha256}",
                    availability = AttachmentAvailability.AVAILABLE,
                    capturedAt = Instant.now().toString(),
                ).validate()
                val amended = current.copy(
                    revisionId = MaintenanceIds.maintenanceRevision(),
                    supersedesRevisionId = current.revisionId,
                    attachments = current.attachments + metadata,
                    completionClaims = current.completionClaims,
                    actor = LocalMaintenanceIdentity.owner(this),
                    createdAt = Instant.now().toString(),
                    amendmentReason = "Attached maintenance evidence: $displayName",
                ).validate()
                db.amendMaintenanceRecord(amended)
                selectedRecordId = amended.recordId
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    refreshRecords()
                    showReceipt(
                        title = "Encrypted evidence attached",
                        message = buildString {
                            append(displayName).append("\n\n")
                            append("Bytes: ").append(stored.byteCount).append("\n")
                            append("Media type: ").append(stored.mediaType).append("\n")
                            append("SHA-256: ").append(stored.sha256).append("\n")
                            append("Record revision: ").append(amended.revisionId)
                        },
                    )
                }
            } catch (error: Exception) {
                showOperationFailure("Attachment could not be stored", error.message ?: error.javaClass.simpleName)
            } finally {
                bytes?.fill(0)
            }
        }
    }

    private fun chooseMaintenanceArchiveDestination() {
        val vehicle = currentVehicle()
        if (vehicle == null) {
            toast("Create or select a vehicle before exporting its maintenance archive.")
            return
        }
        pendingArchiveVehicleId = vehicle.vehicleId
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(
                    Intent.EXTRA_TITLE,
                    MaintenanceDocumentTransfer.archiveFileName(vehicle.displayName, Instant.now().epochSecond),
                )
            },
            MAINTENANCE_ARCHIVE_EXPORT_REQUEST,
        )
    }

    private fun writeMaintenanceArchive(uri: Uri) {
        val vehicleId = pendingArchiveVehicleId.also { pendingArchiveVehicleId = null }
        if (vehicleId == null) {
            showOperationFailure("Archive could not be exported", "The selected vehicle context expired.")
            return
        }
        statusLine("Building verified maintenance archive…", R.color.vhos_accent)
        worker.execute {
            var payloadBytes: ByteArray? = null
            try {
                val db = database ?: EvidenceDatabase.open(applicationContext).also { database = it }
                val payload = db.maintenanceArchivePayload(vehicleId)
                payloadBytes = MaintenanceDocumentTransfer.payloadJson(payload)
                val archiveAttachments = db.maintenanceAttachmentsForArchive(payload).map { stored ->
                    val body = stored.bytes()
                    try {
                        MaintenanceArchiveCodec.Attachment(body, stored.mediaType)
                    } finally {
                        body.fill(0)
                    }
                }
                val receipt = contentResolver.openOutputStream(uri, "w")?.use { output ->
                    MaintenanceArchiveCodec.write(
                        output = output,
                        payload = requireNotNull(payloadBytes),
                        payloadSchema = dev.vhos.maintenance.MaintenanceLedgerArchivePayload.PAYLOAD_SCHEMA,
                        attachments = archiveAttachments,
                    )
                } ?: throw IllegalStateException("Android did not provide a writable archive stream.")
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    statusLine(
                        "Maintenance archive exported • ${receipt.archiveLength} bytes • SHA-256 ${receipt.archiveSha256.take(12)}…",
                        R.color.vhos_pass,
                    )
                    showReceipt(
                        title = "Maintenance archive exported",
                        message = buildString {
                            append("Vehicle: ").append(vehicleId).append("\n")
                            append("Payload: ").append(payload.recordRevisions.size)
                                .append(" record revisions, ").append(payload.auditEvents.size)
                                .append(" audit events\n")
                            append("Attachments: ").append(receipt.attachmentSha256.size).append("\n")
                            append("Archive bytes: ").append(receipt.archiveLength).append("\n")
                            append("Archive SHA-256: ").append(receipt.archiveSha256).append("\n")
                            append("Manifest SHA-256: ").append(receipt.manifestSha256)
                        },
                    )
                }
            } catch (error: Exception) {
                showOperationFailure("Archive could not be exported", error.message ?: error.javaClass.simpleName)
            } finally {
                payloadBytes?.fill(0)
            }
        }
    }

    private fun documentDisplayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }

    private fun showReceipt(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showOperationFailure(title: String, message: String) = runOnUiThread {
        if (destroyed) return@runOnUiThread
        statusLine("$title: $message", R.color.vhos_blocked)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(getColor(R.color.vhos_background))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("VHOS MAINTENANCE GARAGE"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(actionButton("Refresh", ::refreshVehicles))
            addView(actionButton("Plan & configuration") {
                currentVehicle()?.let { vehicle ->
                    startActivity(MaintenancePlanActivity.intent(this@MaintenanceActivity, vehicle.vehicleId))
                } ?: toast("Create or select a vehicle first.")
            })
            addView(actionButton("Vehicle Health") { finish() })
        })
        status = TextView(this).apply {
            text = "Opening encrypted maintenance ledger…"
            textSize = 14f
            setTextColor(getColor(R.color.vhos_muted))
            setBackgroundColor(getColor(R.color.vhos_surface))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })

        val vehicleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        vehicleBar.addView(label("Vehicle"))
        vehicleSpinner = Spinner(this).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressVehicleCallback || position !in vehicles.indices) return
                    val next = vehicles[position].vehicleId
                    if (next != selectedVehicleId) {
                        selectedVehicleId = next
                        selectedRecordId = null
                        refreshRecords()
                    }
                }
            }
        }
        vehicleBar.addView(vehicleSpinner, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        vehicleBar.addView(actionButton("New vehicle") { showVehicleDialog(null) })
        vehicleBar.addView(actionButton("Edit vehicle") {
            currentVehicle()?.let(::showVehicleDialog) ?: toast("Create or select a vehicle first.")
        })
        vehicleBar.addView(actionButton("Export archive", ::chooseMaintenanceArchiveDestination))
        root.addView(vehicleBar)

        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queryInput = input("Search title, notes, provider, system…").apply {
            setSingleLine(true)
        }
        filterBar.addView(queryInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        typeFilter = Spinner(this).apply {
            adapter = textAdapter(listOf("All event types") + MaintenanceEventType.entries.map(::eventLabel))
        }
        filterBar.addView(typeFilter, LinearLayout.LayoutParams(dp(190), dp(48)).apply { marginEnd = dp(8) })
        includeVoided = CheckBox(this).apply {
            text = "Voided"
            setTextColor(getColor(R.color.vhos_text))
        }
        filterBar.addView(includeVoided)
        filterBar.addView(actionButton("Apply", ::refreshRecords))
        filterBar.addView(actionButton("New component") {
            currentVehicle()?.let { showComponentDialog(it, null) }
                ?: toast("Create or select a vehicle first.")
        })
        filterBar.addView(actionButton("Components") {
            currentVehicle()?.let(::showComponentRegistry)
                ?: toast("Create or select a vehicle first.")
        })
        filterBar.addView(actionButton("New record") {
            val vehicle = currentVehicle()
            if (vehicle == null) toast("Create or select a vehicle first.")
            else showRecordDialog(vehicle, null)
        })
        root.addView(filterBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), dp(12))
        }
        panes.addView(ScrollView(this).apply { addView(recordList) }, LinearLayout.LayoutParams(0, -1, .34f))

        val recordPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(getColor(R.color.vhos_surface))
        }
        recordPane.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle("Record detail"), LinearLayout.LayoutParams(0, -2, 1f))
            amendButton = actionButton("Amend") {
                val vehicle = currentVehicle()
                val record = currentRecord()
                if (vehicle != null && record != null) showRecordDialog(vehicle, record)
            }
            attachButton = actionButton("Attach receipt / photo") { chooseMaintenanceAttachment() }
            voidButton = actionButton("Void") { showVoidDialog() }
            addView(amendButton)
            addView(attachButton)
            addView(voidButton)
        })
        detail = bodyText("Select a maintenance record to inspect its evidence and lineage.")
        recordPane.addView(detail)
        panes.addView(ScrollView(this).apply { addView(recordPane) }, LinearLayout.LayoutParams(0, -1, .39f))

        val historyPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        historyPane.addView(sectionTitle("Immutable history"))
        history = bodyText("Revision and audit history appears after a record is selected.")
        historyPane.addView(history)
        panes.addView(ScrollView(this).apply { addView(historyPane) }, LinearLayout.LayoutParams(0, -1, .27f))
        root.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        renderRecords()
        return root
    }

    private fun refreshVehicles() {
        val request = ++generation
        statusLine("Loading vehicle assets…", R.color.vhos_accent)
        worker.execute {
            try {
                val db = database ?: EvidenceDatabase.open(applicationContext).also { database = it }
                val loaded = db.currentVehicleAssets()
                runOnUiThread {
                    if (!isCurrent(request)) return@runOnUiThread
                    vehicles = loaded
                    val retained = selectedVehicleId?.takeIf { id -> loaded.any { it.vehicleId == id } }
                    selectedVehicleId = retained ?: loaded.firstOrNull()?.vehicleId
                    renderVehicles()
                    refreshRecords()
                }
            } catch (error: RuntimeException) {
                showFailure(request, "Vehicle inventory could not be loaded", error)
            }
        }
    }

    private fun renderVehicles() {
        suppressVehicleCallback = true
        vehicleSpinner.adapter = textAdapter(
            if (vehicles.isEmpty()) listOf("No vehicles — create one")
            else vehicles.map { "${it.displayName}  •  ${it.modelYear} ${it.make} ${it.model}" },
        )
        val selectedIndex = vehicles.indexOfFirst { it.vehicleId == selectedVehicleId }.coerceAtLeast(0)
        vehicleSpinner.setSelection(selectedIndex, false)
        suppressVehicleCallback = false
    }

    private fun refreshRecords() {
        val vehicleId = selectedVehicleId
        if (vehicleId == null) {
            components = emptyList()
            requirements = emptyList()
            records = emptyList()
            selectedRecordId = null
            renderRecords()
            statusLine("No vehicle asset exists yet. Create one to begin a truthful maintenance ledger.", R.color.vhos_check)
            return
        }
        val request = ++generation
        val text = queryInput.text.toString().trim().ifBlank { null }
        val selectedType = typeFilter.selectedItemPosition.takeIf { it > 0 }
            ?.let { MaintenanceEventType.entries[it - 1] }
        val showVoided = includeVoided.isChecked
        statusLine("Loading maintenance records…", R.color.vhos_accent)
        worker.execute {
            try {
                val db = requireNotNull(database)
                val loadedComponents = db.currentVehicleComponents(vehicleId, includeRetired = true)
                val loadedRequirements = db.currentMaintenanceRequirements(vehicleId)
                val loaded = if (text != null || selectedType != null) {
                    db.searchMaintenanceRecords(
                        MaintenanceSearchQuery(
                            vehicleId = vehicleId,
                            text = text,
                            eventTypes = selectedType?.let(::setOf) ?: emptySet(),
                            includeVoided = showVoided,
                        ),
                    )
                } else {
                    db.currentMaintenanceRecords(vehicleId, showVoided)
                }
                runOnUiThread {
                    if (!isCurrent(request)) return@runOnUiThread
                    components = loadedComponents
                    requirements = loadedRequirements
                    records = loaded
                    selectedRecordId = selectedRecordId?.takeIf { id -> loaded.any { it.recordId == id } }
                        ?: loaded.firstOrNull()?.recordId
                    renderRecords()
                    statusLine(
                        "${currentVehicle()?.displayName ?: "Vehicle"}: ${loaded.size} current record${if (loaded.size == 1) "" else "s"}; ${loadedComponents.size} registered component${if (loadedComponents.size == 1) "" else "s"}. Absence of a record is UNKNOWN, never healthy.",
                        if (loaded.isEmpty()) R.color.vhos_check else R.color.vhos_pass,
                    )
                    loadSelectedHistory()
                }
            } catch (error: RuntimeException) {
                showFailure(request, "Maintenance records could not be loaded", error)
            }
        }
    }

    private fun renderRecords() {
        recordList.removeAllViews()
        recordList.addView(sectionTitle("Maintenance timeline"))
        if (records.isEmpty()) {
            recordList.addView(bodyText("No matching records. This is an unknown/unrecorded state, not evidence that service is current."))
        } else {
            records.forEach { record ->
                recordList.addView(Button(this).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = maintenanceRecordListLabel(record)
                    setTextColor(getColor(R.color.vhos_text))
                    setBackgroundColor(getColor(if (record.recordId == selectedRecordId) R.color.vhos_accent else R.color.vhos_surface))
                    setOnClickListener {
                        selectedRecordId = record.recordId
                        renderRecords()
                        loadSelectedHistory()
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            }
        }
        val selected = currentRecord()
        detail.text = selected?.let(::recordDetail)
            ?: "Select a maintenance record to inspect its evidence and lineage."
        val mutable = selected?.state == MaintenanceRecordState.ACTIVE
        amendButton.isEnabled = mutable
        attachButton.isEnabled = mutable
        voidButton.isEnabled = mutable
        if (selected == null) history.text = "Revision and audit history appears after a record is selected."
    }

    private fun loadSelectedHistory() {
        val record = currentRecord() ?: return
        detail.text = recordDetail(record)
        val request = ++generation
        worker.execute {
            try {
                val db = requireNotNull(database)
                val revisions = db.maintenanceRecordHistory(record.vehicleId, record.recordId)
                val audit = db.maintenanceAuditTrail(record.vehicleId, record.recordId)
                runOnUiThread {
                    if (!isCurrent(request) || selectedRecordId != record.recordId) return@runOnUiThread
                    history.text = historyText(revisions, audit)
                }
            } catch (error: RuntimeException) {
                showFailure(request, "Record history could not be loaded", error)
            }
        }
    }

    private fun showVehicleDialog(existing: VehicleAsset?) {
        val form = formLayout()
        val displayName = form.field("Display name *", existing?.displayName)
        val year = form.field("Model year *", existing?.modelYear?.toString(), InputType.TYPE_CLASS_NUMBER)
        val make = form.field("Make *", existing?.make)
        val model = form.field("Model *", existing?.model)
        val trim = form.field("Trim", existing?.trim)
        val vin = form.field("VIN (17 characters)", existing?.vin)
        val plate = form.field("License plate", existing?.licensePlate)
        val unit = form.spinner("Distance unit", DistanceUnit.entries.map(::enumLabel), existing?.distanceUnit?.ordinal ?: 0)
        if (existing != null) {
            unit.isEnabled = false
            form.section("Distance unit is fixed after vehicle creation so stored odometer values are never silently reinterpreted.")
        }
        val odometer = form.field("Current odometer", existing?.currentOdometer?.value?.toString(), InputType.TYPE_CLASS_NUMBER)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Create vehicle asset" else "Append vehicle revision")
            .setView(ScrollView(this).apply { addView(form.root) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Create" else "Save revision", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val distanceUnit = DistanceUnit.entries[unit.selectedItemPosition]
                    val reading = odometer.valueOrNull()?.let { OdometerReading(it.toLong(), distanceUnit) }
                    val asset = if (existing == null) {
                        VehicleAsset(
                            displayName = displayName.required("Display name"),
                            modelYear = year.required("Model year").toInt(),
                            make = make.required("Make"),
                            model = model.required("Model"),
                            trim = trim.valueOrNull(), vin = vin.valueOrNull()?.uppercase(Locale.US),
                            licensePlate = plate.valueOrNull(), distanceUnit = distanceUnit,
                            currentOdometer = reading,
                        )
                    } else {
                        existing.copy(
                            revisionId = MaintenanceIds.vehicleRevision(), supersedesRevisionId = existing.revisionId,
                            createdAt = Instant.now().toString(), displayName = displayName.required("Display name"),
                            modelYear = year.required("Model year").toInt(), make = make.required("Make"),
                            model = model.required("Model"), trim = trim.valueOrNull(),
                            vin = vin.valueOrNull()?.uppercase(Locale.US), licensePlate = plate.valueOrNull(),
                            distanceUnit = distanceUnit, currentOdometer = reading,
                            // Vehicle Pack applicability is revision-specific. Any identity or odometer
                            // amendment must return to UNKNOWN until the Plan screen re-evaluates the
                            // new vehicle revision against the signed pack manifest.
                            activeVehiclePack = null,
                        )
                    }.validate()
                    appendVehicle(asset) { dialog.dismiss() }
                } catch (error: RuntimeException) {
                    toast(error.message ?: "Vehicle values are invalid.")
                }
            }
        }
        dialog.show()
    }

    private fun appendVehicle(asset: VehicleAsset, onSuccess: () -> Unit = {}) {
        statusLine("Saving append-only vehicle revision…", R.color.vhos_accent)
        worker.execute {
            try {
                requireNotNull(database).appendVehicleAsset(asset)
                selectedVehicleId = asset.vehicleId
                runOnUiThread {
                    if (!destroyed) {
                        onSuccess()
                        refreshVehicles()
                    }
                }
            } catch (error: RuntimeException) {
                runOnUiThread { toast(error.message ?: "Vehicle revision was not saved.") }
            }
        }
    }

    private fun showComponentDialog(vehicle: VehicleAsset, existing: VehicleComponentRevision?) {
        val form = formLayout()
        val systemId = form.field("Stable system ID * (for example brakes)", existing?.systemId)
        val systemName = form.field("System display name *", existing?.systemDisplayName)
        val componentName = form.field("Component display name *", existing?.displayName)
        val manufacturer = form.field("Manufacturer", existing?.manufacturer)
        val partNumber = form.field("Part number", existing?.partNumber)
        val serialNumber = form.field("Serial number", existing?.serialNumber)
        val installedAt = form.field(
            "Installed at (ISO-8601 instant)",
            existing?.installedAt,
        )
        val parentCandidates = components.filter {
            it.componentId != existing?.componentId &&
                (it.state == VehicleComponentState.ACTIVE || it.componentId == existing?.parentComponentId)
        }
        val parentChoices = listOf("No parent") + parentCandidates.map {
            "${it.displayName} • ${it.systemId} • ${it.state.name}"
        }
        val selectedParentIndex = existing?.parentComponentId?.let { parentId ->
            parentCandidates.indexOfFirst { it.componentId == parentId }
                .takeIf { it >= 0 }
                ?.plus(1)
        } ?: 0
        val parentPicker = form.spinner("Parent component", parentChoices, selectedParentIndex)
        val customEditors = mutableListOf<CustomFieldInputs>()
        val customContainer = form.dynamicSection("Typed component fields", "Add custom field") { container ->
            addCustomFieldEditor(form, container, customEditors, null)
        }
        existing?.customFields?.forEach { addCustomFieldEditor(form, customContainer, customEditors, it) }
        val amendmentReason = if (existing != null) {
            form.field("Reason for component amendment *", null, multiline = true)
        } else null
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Register vehicle component" else "Append component revision")
            .setMessage("The component identity remains stable across service records and future renames.")
            .setView(ScrollView(this).apply { addView(form.root) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Register" else "Save revision", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val component = VehicleComponentRevision(
                        vehicleId = vehicle.vehicleId,
                        componentId = existing?.componentId ?: MaintenanceIds.component(),
                        revisionId = if (existing == null) {
                            MaintenanceIds.componentRevision()
                        } else {
                            MaintenanceIds.componentRevision()
                        },
                        supersedesRevisionId = existing?.revisionId,
                        systemId = systemId.required("System ID"),
                        systemDisplayName = systemName.required("System name"),
                        displayName = componentName.required("Component name"),
                        parentComponentId = parentPicker.selectedItemPosition.takeIf { it > 0 }
                            ?.let { parentCandidates[it - 1].componentId },
                        state = existing?.state ?: VehicleComponentState.ACTIVE,
                        manufacturer = manufacturer.valueOrNull(),
                        partNumber = partNumber.valueOrNull(),
                        serialNumber = serialNumber.valueOrNull(),
                        installedAt = installedAt.valueOrNull()?.let { Instant.parse(it).toString() },
                        customFields = customEditors.mapNotNull(::customField),
                        actor = LocalMaintenanceIdentity.owner(this),
                        amendmentReason = amendmentReason?.required("Component amendment reason"),
                    ).validate()
                    statusLine("Appending durable component registry entry…", R.color.vhos_accent)
                    worker.execute {
                        try {
                            requireNotNull(database).appendVehicleComponent(component)
                            runOnUiThread {
                                if (!destroyed) {
                                    refreshRecords()
                                    toast("Registered ${component.displayName} with stable ID ${component.componentId}.")
                                    dialog.dismiss()
                                }
                            }
                        } catch (error: RuntimeException) {
                            runOnUiThread { toast(error.message ?: "Component was not registered.") }
                        }
                    }
                } catch (error: RuntimeException) {
                    toast(error.message ?: "Component values are invalid.")
                }
            }
        }
        dialog.show()
    }

    private fun showComponentRegistry(vehicle: VehicleAsset) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (components.isEmpty()) {
            content.addView(bodyText(
                "No physical components are registered. Records can still be saved with an explicit unknown component.",
            ))
        } else {
            components.sortedWith(compareBy({ it.systemId }, { it.displayName.lowercase(Locale.US) }))
                .forEach { component ->
                    content.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        setBackgroundColor(getColor(R.color.vhos_surface))
                        addView(bodyText(buildString {
                            append(component.displayName).append("  •  ").append(component.state.name).append('\n')
                            append(component.systemDisplayName).append(" (").append(component.systemId).append(")")
                            component.manufacturer?.let { append("\nManufacturer: ").append(it) }
                            component.partNumber?.let { append("\nPart: ").append(it) }
                            component.serialNumber?.let { append("\nSerial: ").append(it) }
                            component.installedAt?.let { append("\nInstalled: ").append(it) }
                            component.retiredAt?.let { append("\nRetired: ").append(it) }
                            if (component.customFields.isNotEmpty()) {
                                append("\nCustom data:")
                                component.customFields.forEach { field ->
                                    append("\n  • ").append(field.label).append(": ")
                                        .append(field.value)
                                        .append(field.unit?.let { unit -> " $unit" } ?: "")
                                        .append(" [").append(enumLabel(field.type)).append(']')
                                }
                            }
                            append("\nComponent: ").append(component.componentId)
                            append("\nRevision: ").append(component.revisionId)
                        }))
                        addView(LinearLayout(this@MaintenanceActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(actionButton("History") { showComponentHistory(component) })
                            if (component.state == VehicleComponentState.ACTIVE) {
                                addView(actionButton("Amend") { showComponentDialog(vehicle, component) })
                                addView(actionButton("Retire") { showRetireComponentDialog(component) })
                            }
                        })
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
        }
        AlertDialog.Builder(this)
            .setTitle("Physical component registry")
            .setMessage("Updates and retirement append immutable revisions; prior service keeps the same stable component identity.")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
            .setPositiveButton("Register component") { _, _ -> showComponentDialog(vehicle, null) }
            .show()
    }

    private fun showComponentHistory(component: VehicleComponentRevision) {
        worker.execute {
            try {
                val revisions = requireNotNull(database).vehicleComponentHistory(
                    component.vehicleId,
                    component.componentId,
                )
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    showReceipt("${component.displayName} history", buildString {
                        revisions.forEachIndexed { index, revision ->
                            append('#').append(index + 1).append("  ").append(revision.createdAt).append('\n')
                            append(revision.state.name).append(" • ").append(revision.revisionId).append('\n')
                            revision.amendmentReason?.let { append(it).append('\n') }
                            append('\n')
                        }
                    })
                }
            } catch (error: RuntimeException) {
                showOperationFailure("Component history could not be loaded", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun showRetireComponentDialog(component: VehicleComponentRevision) {
        val reason = input("Why is this physical component being retired?").apply {
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Retire ${component.displayName}")
            .setMessage("The component and all linked service history remain permanent.")
            .setView(reason)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Append retirement", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val now = Instant.now().toString()
                    val retired = component.copy(
                        revisionId = MaintenanceIds.componentRevision(),
                        supersedesRevisionId = component.revisionId,
                        state = VehicleComponentState.RETIRED,
                        retiredAt = now,
                        actor = LocalMaintenanceIdentity.owner(this),
                        createdAt = now,
                        amendmentReason = reason.required("Retirement reason"),
                    ).validate()
                    statusLine("Appending component retirement…", R.color.vhos_accent)
                    worker.execute {
                        try {
                            requireNotNull(database).appendVehicleComponent(retired)
                            runOnUiThread {
                                if (destroyed) return@runOnUiThread
                                dialog.dismiss()
                                refreshRecords()
                                toast("${retired.displayName} retired; history preserved.")
                            }
                        } catch (error: RuntimeException) {
                            runOnUiThread { toast(error.message ?: "Component was not retired.") }
                        }
                    }
                } catch (error: RuntimeException) {
                    toast(error.message ?: "A retirement reason is required.")
                }
            }
        }
        dialog.show()
    }

    private fun showRecordDialog(vehicle: VehicleAsset, existing: MaintenanceRecordRevision?) {
        if (existing?.state == MaintenanceRecordState.VOIDED) {
            toast("Voided records remain immutable and cannot be amended.")
            return
        }
        val form = formLayout()
        val eventType = form.spinner("Event type *", MaintenanceEventType.entries.map(::eventLabel), existing?.eventType?.ordinal ?: 0)
        val title = form.field("Title *", existing?.title)
        val occurrencePrecision = form.spinner(
            "Occurrence precision *",
            listOf("Exact date and time", "Date only / time unknown"),
            if (existing?.occurrencePrecision == MaintenanceOccurrencePrecision.DATE_ONLY) 1 else 0,
        )
        val occurredAt = form.field(
            "Occurred at * (ISO-8601 instant or YYYY-MM-DD)",
            existing?.occurredAt ?: Instant.now().toString(),
        )
        occurrencePrecision.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val current = occurredAt.text.toString().trim()
                if (current.isEmpty()) return
                val converted = runCatching {
                    if (position == 1) {
                        Instant.parse(current).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    } else {
                        LocalDate.parse(current).atStartOfDay(ZoneOffset.UTC).toInstant().toString()
                    }
                }.getOrNull()
                if (converted != null && converted != current) occurredAt.setText(converted)
            }
        }
        val odometer = form.field("Odometer (${enumLabel(vehicle.distanceUnit)})", existing?.odometer?.value?.toString(), InputType.TYPE_CLASS_NUMBER)
        val engineHours = form.field("Engine hours", existing?.engineHours, decimalInput())
        form.section("Vehicle system and component")
        val existingComponentIds = existing?.components?.mapNotNull { it.componentId }?.toSet().orEmpty()
        val selectableComponents = components.filter {
            it.state == VehicleComponentState.ACTIVE || it.componentId in existingComponentIds
        }
        val selectedComponentIds = existingComponentIds.toMutableSet()
        var includeUnknownComponent = existing == null ||
            existing.components.any { it.componentId == null }
        val componentSummary = bodyText("")
        fun updateComponentSummary() {
            val names = buildList {
                if (includeUnknownComponent) add("Unknown / unrecorded system and component")
                addAll(selectableComponents.filter { it.componentId in selectedComponentIds }.map { component ->
                    "${component.displayName} • ${component.systemDisplayName} • ${component.state.name}"
                })
            }
            componentSummary.text = if (names.isEmpty()) {
                "No component evidence selected. Select at least one known component or explicit unknown."
            } else {
                names.joinToString("\n") { "• $it" }
            }
        }
        updateComponentSummary()
        form.root.addView(componentSummary)
        form.root.addView(actionButton("Choose one or more components") {
            val labels = listOf("Unknown / unrecorded system and component") + selectableComponents.map {
                "${it.displayName} • ${it.systemDisplayName} • ${it.state.name}"
            }
            val checked = BooleanArray(labels.size) { index ->
                if (index == 0) includeUnknownComponent
                else selectableComponents[index - 1].componentId in selectedComponentIds
            }
            AlertDialog.Builder(this)
                .setTitle("Component evidence")
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply") { _, _ ->
                    includeUnknownComponent = checked[0]
                    selectedComponentIds.clear()
                    selectableComponents.forEachIndexed { index, component ->
                        if (checked[index + 1]) selectedComponentIds += component.componentId
                    }
                    updateComponentSummary()
                }
                .show()
        })
        val completionRequirements = requirements.filter {
            it.authority == MaintenanceRequirementAuthority.OWNER_CUSTOM &&
                it.vehicleAssetRevisionId == vehicle.revisionId
        }
        val availableCompletionRequirements = completionRequirements
            .filterNot { requirement ->
                existing?.completionClaims?.any { it.taskId == requirement.task.taskId } == true
            }
        val canClearCompletionClaims = !existing?.completionClaims.isNullOrEmpty()
        val completionChoices = buildList {
            add(if (existing == null) "No explicit completion claim" else "Keep existing completion claims")
            if (canClearCompletionClaims) add("Clear existing completion claims (correction)")
            addAll(availableCompletionRequirements.map { "Owner attestation: ${it.task.title}" })
        }
        val completionPicker = form.spinner(
            "Maintenance-plan completion",
            completionChoices,
            0,
        )
        form.section("Provider and total cost")
        val providerName = form.field("Provider / shop", existing?.provider?.name)
        val providerPhone = form.field("Provider phone", existing?.provider?.phone, InputType.TYPE_CLASS_PHONE)
        val providerEmail = form.field("Provider email", existing?.provider?.email, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val providerAddress = form.field("Provider address", existing?.provider?.address, multiline = true)
        val invoice = form.field("Invoice number", existing?.provider?.invoiceNumber)
        val currency = form.field("Currency", existing?.totalCost?.currencyCode ?: "USD")
        val totalCost = form.field("Total cost", existing?.totalCost?.let(::moneyDecimal), decimalInput())

        val itemEditors = mutableListOf<LineItemInputs>()
        val itemContainer = form.dynamicSection("Parts, fluids, supplies, labor", "Add line item") { container ->
            addLineItemEditor(form, container, itemEditors, null)
        }
        existing?.lineItems?.forEach { addLineItemEditor(form, itemContainer, itemEditors, it) }

        val measurementEditors = mutableListOf<MeasurementInputs>()
        val measurementContainer = form.dynamicSection("Measurements and inspection facts", "Add measurement") { container ->
            addMeasurementEditor(form, container, measurementEditors, null)
        }
        existing?.measurements?.forEach { addMeasurementEditor(form, measurementContainer, measurementEditors, it) }

        form.section("Warranty")
        val warrantyProvider = form.field("Warranty provider", existing?.warranty?.provider)
        val warrantyStart = form.field("Warranty starts (YYYY-MM-DD)", existing?.warranty?.startsOn)
        val warrantyEnd = form.field("Warranty expires (YYYY-MM-DD)", existing?.warranty?.expiresOn)
        val warrantyDistance = form.field("Warranty distance limit", existing?.warranty?.distanceLimit?.value?.toString(), InputType.TYPE_CLASS_NUMBER)
        val warrantyTerms = form.field("Warranty terms", existing?.warranty?.terms, multiline = true)
        val notes = form.field("Notes", existing?.notes, multiline = true)

        val customEditors = mutableListOf<CustomFieldInputs>()
        val customContainer = form.dynamicSection("Typed custom fields", "Add custom field") { container ->
            addCustomFieldEditor(form, container, customEditors, null)
        }
        existing?.customFields?.forEach { addCustomFieldEditor(form, customContainer, customEditors, it) }
        val amendmentReason = if (existing != null) form.field("Reason for amendment *", null, multiline = true) else null

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Create maintenance record" else "Append maintenance revision")
            .setView(ScrollView(this).apply { addView(form.root) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Create" else "Save revision", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val provider = providerName.valueOrNull()?.let {
                        MaintenanceProvider(
                            name = it,
                            phone = providerPhone.valueOrNull(),
                            email = providerEmail.valueOrNull(),
                            address = providerAddress.valueOrNull(),
                            invoiceNumber = invoice.valueOrNull(),
                        )
                    }
                    val cost = totalCost.valueOrNull()?.let { money(currency.required("Currency"), it) }
                    val warranty = warrantyProvider.valueOrNull()?.let {
                        WarrantyInfo(
                            provider = it, startsOn = warrantyStart.required("Warranty start date"),
                            expiresOn = warrantyEnd.valueOrNull(),
                            distanceLimit = warrantyDistance.valueOrNull()?.let { value ->
                                OdometerReading(value.toLong(), vehicle.distanceUnit)
                            },
                            terms = warrantyTerms.valueOrNull(),
                            documentAttachmentIds = existing?.warranty?.documentAttachmentIds ?: emptyList(),
                        )
                    }
                    val selectedComponents = selectableComponents.filter {
                        it.componentId in selectedComponentIds
                    }
                    require(includeUnknownComponent || selectedComponents.isNotEmpty()) {
                        "Select at least one component or explicit unknown evidence."
                    }
                    val editedSystems = buildList {
                        addAll(selectedComponents.map {
                            MaintenanceSystemRef(it.systemId, it.systemDisplayName)
                        }.distinctBy { it.systemId })
                        if (includeUnknownComponent) add(MaintenanceSystemRef.unknown())
                    }
                    val editedComponents = buildList {
                        addAll(selectedComponents.map { it.asMaintenanceRef() })
                        if (includeUnknownComponent) add(MaintenanceComponentRef.unknown())
                    }
                    val selectedPrecision = if (occurrencePrecision.selectedItemPosition == 1) {
                        MaintenanceOccurrencePrecision.DATE_ONLY
                    } else {
                        MaintenanceOccurrencePrecision.INSTANT
                    }
                    val occurrenceValue = when (selectedPrecision) {
                        MaintenanceOccurrencePrecision.DATE_ONLY ->
                            LocalDate.parse(occurredAt.required("Occurrence date")).toString()
                        MaintenanceOccurrencePrecision.INSTANT ->
                            Instant.parse(occurredAt.required("Occurrence instant")).toString()
                    }
                    val completionClaims = when {
                        completionPicker.selectedItemPosition == 0 -> existing?.completionClaims ?: emptyList()
                        canClearCompletionClaims && completionPicker.selectedItemPosition == 1 -> emptyList()
                        else -> {
                            val requirementIndex = completionPicker.selectedItemPosition - 1 -
                                if (canClearCompletionClaims) 1 else 0
                            val requirement = availableCompletionRequirements[requirementIndex]
                            (existing?.completionClaims ?: emptyList()) + MaintenanceCompletionClaim(
                                taskId = requirement.task.taskId,
                                requirementId = requirement.requirementId,
                                trust = MaintenanceCompletionTrust.OWNER_ATTESTED,
                            )
                        }
                    }
                    val base = MaintenanceRecordRevision(
                        recordId = existing?.recordId ?: MaintenanceIds.maintenanceRecord(),
                        revisionId = MaintenanceIds.maintenanceRevision(),
                        supersedesRevisionId = existing?.revisionId,
                        vehicleId = vehicle.vehicleId,
                        vehicleAssetRevisionId = vehicle.revisionId,
                        eventType = MaintenanceEventType.entries[eventType.selectedItemPosition],
                        title = title.required("Title"), occurredAt = occurrenceValue,
                        occurrencePrecision = selectedPrecision,
                        odometer = odometer.valueOrNull()?.let { OdometerReading(it.toLong(), vehicle.distanceUnit) },
                        engineHours = engineHours.valueOrNull(),
                        systems = editedSystems,
                        components = editedComponents,
                        provider = provider, totalCost = cost,
                        lineItems = itemEditors.mapNotNull(::lineItem),
                        measurements = measurementEditors.mapNotNull(::measurement),
                        warranty = warranty, notes = notes.valueOrNull(),
                        customFields = customEditors.mapNotNull(::customField),
                        attachments = existing?.attachments ?: emptyList(),
                        completionClaims = completionClaims,
                        actor = LocalMaintenanceIdentity.owner(this),
                        amendmentReason = amendmentReason?.required("Amendment reason"),
                    ).validate()
                    saveRecord(base, existing == null) { dialog.dismiss() }
                } catch (error: RuntimeException) {
                    toast(error.message ?: "Maintenance values are invalid.")
                }
            }
        }
        dialog.show()
    }

    private fun saveRecord(
        record: MaintenanceRecordRevision,
        create: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        statusLine(if (create) "Creating maintenance record…" else "Appending maintenance revision…", R.color.vhos_accent)
        worker.execute {
            try {
                val saved = if (create) requireNotNull(database).createMaintenanceRecord(record)
                else requireNotNull(database).amendMaintenanceRecord(record)
                selectedRecordId = saved.recordId
                runOnUiThread {
                    if (!destroyed) {
                        onSuccess()
                        refreshRecords()
                    }
                }
            } catch (error: RuntimeException) {
                runOnUiThread { toast(error.message ?: "Maintenance revision was not saved.") }
            }
        }
    }

    private fun showVoidDialog() {
        val record = currentRecord() ?: return
        if (record.state == MaintenanceRecordState.VOIDED) return
        val reason = input("Why is this record invalid?").apply {
            minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Void record without deleting history")
            .setMessage("The original record and all revisions remain in the audit trail.")
            .setView(reason)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Void", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val request = VoidMaintenanceRecordRequest(
                        record.vehicleId,
                        record.recordId,
                        LocalMaintenanceIdentity.owner(this),
                        reason.required("Void reason"),
                    )
                    worker.execute {
                        try {
                            requireNotNull(database).voidMaintenanceRecord(request)
                            runOnUiThread {
                                if (!destroyed) {
                                    dialog.dismiss()
                                    refreshRecords()
                                }
                            }
                        } catch (error: RuntimeException) {
                            runOnUiThread { toast(error.message ?: "Record was not voided.") }
                        }
                    }
                } catch (error: RuntimeException) {
                    toast(error.message ?: "A void reason is required.")
                }
            }
        }
        dialog.show()
    }

    private fun addLineItemEditor(form: Form, container: LinearLayout, rows: MutableList<LineItemInputs>, value: PartFluidLineItem?) {
        val row = form.rowCard()
        val type = row.spinner("Type", LineItemType.entries.map(::enumLabel), value?.type?.ordinal ?: 0)
        val description = row.field("Description *", value?.description)
        val maker = row.field("Manufacturer", value?.manufacturer)
        val part = row.field("Part / fluid number", value?.partNumber)
        val spec = row.field("Specification", value?.specification)
        val quantity = row.field("Quantity *", value?.quantity ?: "1", decimalInput())
        val unit = row.field("Quantity unit *", value?.quantityUnit ?: "each")
        val currency = row.field("Cost currency", value?.cost?.currencyCode ?: "USD")
        val cost = row.field("Line cost", value?.cost?.let(::moneyDecimal), decimalInput())
        val inputs = LineItemInputs(value?.lineItemId ?: MaintenanceIds.lineItem(), type, description, maker, part, spec, quantity, unit, currency, cost, row.root)
        rows += inputs
        row.removeButton("Remove") { rows.remove(inputs); container.removeView(row.root) }
        container.addView(row.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    private fun addMeasurementEditor(form: Form, container: LinearLayout, rows: MutableList<MeasurementInputs>, value: MaintenanceMeasurement?) {
        val row = form.rowCard()
        val name = row.field("Measurement name *", value?.name)
        val type = row.spinner("Value type", MeasurementValueType.entries.map(::enumLabel), value?.valueType?.ordinal ?: 0)
        val measured = row.field("Value *", value?.value)
        val unit = row.field("Unit (required for decimal)", value?.unit)
        val method = row.field("Method", value?.method)
        val grade = row.field("Condition grade", value?.conditionGrade)
        val inputs = MeasurementInputs(value?.measurementId ?: MaintenanceIds.measurement(), name, type, measured, unit, method, grade, row.root)
        rows += inputs
        row.removeButton("Remove") { rows.remove(inputs); container.removeView(row.root) }
        container.addView(row.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    private fun addCustomFieldEditor(form: Form, container: LinearLayout, rows: MutableList<CustomFieldInputs>, value: TypedCustomFieldValue?) {
        val row = form.rowCard()
        val id = row.field("Stable field ID *", value?.fieldId)
        val label = row.field("Label *", value?.label)
        val type = row.spinner("Type", CustomValueType.entries.map(::enumLabel), value?.type?.ordinal ?: 0)
        val customValue = row.field("Value *", value?.value)
        val unit = row.field("Unit (numeric fields only)", value?.unit)
        val inputs = CustomFieldInputs(id, label, type, customValue, unit, row.root)
        rows += inputs
        row.removeButton("Remove") { rows.remove(inputs); container.removeView(row.root) }
        container.addView(row.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    private fun lineItem(row: LineItemInputs): PartFluidLineItem? {
        if (row.description.valueOrNull() == null && row.part.valueOrNull() == null) return null
        return PartFluidLineItem(
            lineItemId = row.id, type = LineItemType.entries[row.type.selectedItemPosition],
            description = row.description.required("Line item description"), manufacturer = row.maker.valueOrNull(),
            partNumber = row.part.valueOrNull(), specification = row.spec.valueOrNull(),
            quantity = row.quantity.required("Line item quantity"), quantityUnit = row.unit.required("Quantity unit"),
            cost = row.cost.valueOrNull()?.let { money(row.currency.required("Line cost currency"), it) },
        )
    }

    private fun measurement(row: MeasurementInputs): MaintenanceMeasurement? {
        if (row.name.valueOrNull() == null && row.value.valueOrNull() == null) return null
        return MaintenanceMeasurement(
            measurementId = row.id, name = row.name.required("Measurement name"),
            valueType = MeasurementValueType.entries[row.type.selectedItemPosition],
            value = row.value.required("Measurement value"), unit = row.unit.valueOrNull(),
            method = row.method.valueOrNull(), conditionGrade = row.grade.valueOrNull(),
        )
    }

    private fun customField(row: CustomFieldInputs): TypedCustomFieldValue? {
        if (row.id.valueOrNull() == null && row.value.valueOrNull() == null) return null
        return TypedCustomFieldValue(
            fieldId = row.id.required("Custom field ID"), label = row.label.required("Custom field label"),
            type = CustomValueType.entries[row.type.selectedItemPosition], value = row.value.required("Custom field value"),
            unit = row.unit.valueOrNull(),
        )
    }

    private fun recordDetail(record: MaintenanceRecordRevision): String = buildString {
        append(record.title).append("\n")
        append(eventLabel(record.eventType)).append("  •  ").append(record.state.name).append("\n")
        append("Occurred: ").append(record.occurredAt)
            .append(" (").append(enumLabel(record.occurrencePrecision)).append(")\n")
        record.odometer?.let { append("Odometer: ").append(formatOdometer(it)).append("\n") }
        record.engineHours?.let { append("Engine hours: ").append(it).append(" h\n") }
        append("System: ").append(record.systems.joinToString { system ->
            if (system.displayName == null || system.systemId == null) {
                "Unknown / unrecorded system"
            } else {
                "${system.displayName} (${system.systemId})"
            }
        }).append("\n")
        append("Components: ").append(record.components.joinToString {
            it.displayName ?: "Unknown / unrecorded component"
        }).append("\n")
        record.provider?.let { provider ->
            append("Provider: ").append(provider.name)
                .append(provider.invoiceNumber?.let { n -> " • invoice $n" } ?: "").append("\n")
            provider.phone?.let { append("Phone: ").append(it).append("\n") }
            provider.email?.let { append("Email: ").append(it).append("\n") }
            provider.address?.let { append("Address: ").append(it).append("\n") }
        }
        record.totalCost?.let { append("Total: ").append(moneyDecimal(it)).append(' ').append(it.currencyCode).append("\n") }
        if (record.lineItems.isNotEmpty()) {
            append("\nPARTS / FLUIDS / LABOR\n")
            record.lineItems.forEach { item ->
                append("• ").append(enumLabel(item.type)).append(": ").append(item.description)
                    .append(" — ").append(item.quantity).append(' ').append(item.quantityUnit).append("\n")
                item.manufacturer?.let { append("  Manufacturer: ").append(it).append("\n") }
                item.partNumber?.let { append("  Part / product: ").append(it).append("\n") }
                item.specification?.let { append("  Specification: ").append(it).append("\n") }
                item.cost?.let {
                    append("  Cost: ").append(moneyDecimal(it)).append(' ').append(it.currencyCode).append("\n")
                }
            }
        }
        if (record.measurements.isNotEmpty()) {
            append("\nMEASUREMENTS\n")
            record.measurements.forEach { measurement ->
                append("• ").append(measurement.name).append(": ").append(measurement.value)
                    .append(measurement.unit?.let { unit -> " $unit" } ?: "")
                    .append(" [").append(enumLabel(measurement.valueType)).append("]\n")
                measurement.method?.let { append("  Method: ").append(it).append("\n") }
                measurement.conditionGrade?.let { append("  Condition: ").append(it).append("\n") }
            }
        }
        record.warranty?.let { warranty ->
            append("\nWARRANTY\n").append(warranty.provider).append(" • ").append(warranty.startsOn)
                .append(warranty.expiresOn?.let { end -> " → $end" } ?: "").append("\n")
            warranty.distanceLimit?.let { append("Distance limit: ").append(formatOdometer(it)).append("\n") }
            warranty.terms?.let { append("Terms: ").append(it).append("\n") }
            if (warranty.documentAttachmentIds.isNotEmpty()) {
                append("Documents: ").append(warranty.documentAttachmentIds.size).append(" encrypted attachment(s)\n")
            }
        }
        record.notes?.let { append("\nNOTES\n").append(it).append("\n") }
        if (record.customFields.isNotEmpty()) {
            append("\nCUSTOM FIELDS\n")
            record.customFields.forEach { append("• ").append(it.label).append(": ").append(it.value).append(it.unit?.let { unit -> " $unit" } ?: "").append(" [").append(enumLabel(it.type)).append("]\n") }
        }
        if (record.attachments.isNotEmpty()) {
            append("\nENCRYPTED EVIDENCE\n")
            record.attachments.forEach { attachment ->
                append("• ").append(attachment.displayName)
                    .append(" — ").append(attachment.byteCount).append(" bytes")
                    .append(" • SHA-256 ").append(attachment.sha256.take(12)).append("…")
                    .append(" • ").append(enumLabel(attachment.availability)).append("\n")
            }
        }
        if (record.completionClaims.isNotEmpty()) {
            append("\nMAINTENANCE PLAN COMPLETION CLAIMS\n")
            record.completionClaims.forEach { claim ->
                val requirement = requirements.firstOrNull { it.requirementId == claim.requirementId }
                    ?: requirements.firstOrNull { it.task.taskId == claim.taskId }
                append("• ").append(requirement?.task?.title ?: "Task ${claim.taskId}")
                    .append(" • ").append(enumLabel(claim.trust))
                    .append(claim.requirementId?.let { requirementId -> " • requirement $requirementId" } ?: "")
                    .append("\n")
            }
        }
        append("\nLINEAGE\nRecord: ").append(record.recordId)
        append("\nRevision: ").append(record.revisionId)
        append("\nVehicle revision: ").append(record.vehicleAssetRevisionId)
        append("\nActor: ").append(record.actor.displayName).append(" (").append(enumLabel(record.actor.source)).append(')')
        record.amendmentReason?.let { append("\nReason: ").append(it) }
    }

    private fun historyText(revisions: List<MaintenanceRecordRevision>, audit: List<MaintenanceAuditEvent>): String = buildString {
        append(revisions.size).append(" revision").append(if (revisions.size == 1) "" else "s").append("\n\n")
        revisions.forEachIndexed { index, revision ->
            append('#').append(index + 1).append("  ").append(revision.createdAt).append("\n")
            append(revision.state.name).append(" • ").append(revision.revisionId.take(8)).append("…\n")
            revision.amendmentReason?.let { append(it).append("\n") }
            append('\n')
        }
        append("AUDIT EVENTS\n")
        if (audit.isEmpty()) append("No audit events returned.")
        audit.forEach { event ->
            append("• ").append(enumLabel(event.action)).append(" — ").append(event.recordedAt).append("\n")
            event.reason?.let { append("  ").append(it).append("\n") }
        }
    }

    private fun currentVehicle(): VehicleAsset? = vehicles.firstOrNull { it.vehicleId == selectedVehicleId }
    private fun currentRecord(): MaintenanceRecordRevision? = records.firstOrNull { it.recordId == selectedRecordId }
    private fun MaintenanceRecordRevision?.orEmptySystems(): List<MaintenanceSystemRef> = this?.systems ?: emptyList()
    private fun MaintenanceRecordRevision?.orEmptyComponents(): List<MaintenanceComponentRef> = this?.components ?: emptyList()
    private fun isCurrent(request: Int): Boolean = !destroyed && request == generation

    private fun showFailure(request: Int, prefix: String, error: RuntimeException) = runOnUiThread {
        if (!isCurrent(request)) return@runOnUiThread
        statusLine("$prefix: ${error.message ?: error::class.java.simpleName}", R.color.vhos_blocked)
    }

    private fun statusLine(message: String, color: Int) = runOnUiThread {
        if (!destroyed && ::status.isInitialized) {
            status.text = message
            status.setTextColor(getColor(color))
        }
    }

    private fun formLayout(): Form = Form(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(16))
    })

    private inner class Form(val root: LinearLayout) {
        fun field(label: String, value: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT, multiline: Boolean = false): EditText {
            root.addView(label(label))
            return input(label).apply {
                this.inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
                if (multiline) minLines = 3 else setSingleLine(true)
                setText(value.orEmpty())
                root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
        fun spinner(label: String, values: List<String>, selected: Int): Spinner {
            root.addView(label(label))
            return Spinner(this@MaintenanceActivity).apply {
                adapter = textAdapter(values); setSelection(selected)
                root.addView(this, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
            }
        }
        fun section(text: String) { root.addView(sectionTitle(text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        fun dynamicSection(text: String, button: String, add: (LinearLayout) -> Unit): LinearLayout {
            section(text)
            val container = LinearLayout(this@MaintenanceActivity).apply { orientation = LinearLayout.VERTICAL }
            root.addView(actionButton(button) { add(container) })
            root.addView(container)
            return container
        }
        fun rowCard(): Form = Form(LinearLayout(this@MaintenanceActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(getColor(R.color.vhos_surface))
        })
        fun removeButton(text: String, action: () -> Unit) { root.addView(actionButton(text, action)) }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.vhos_text))
    }
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.vhos_text)); setPadding(0, dp(6), 0, dp(5))
    }
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 13f; setTextColor(getColor(R.color.vhos_muted)) }
    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setLineSpacing(0f, 1.12f); setTextColor(getColor(R.color.vhos_text)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint; setTextColor(getColor(R.color.vhos_text)); setHintTextColor(getColor(R.color.vhos_muted)); setPadding(dp(10), dp(6), dp(10), dp(6))
    }
    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }; setTextColor(getColor(R.color.vhos_text))
    }
    private fun textAdapter(values: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, values).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    private fun EditText.valueOrNull(): String? = text.toString().trim().ifBlank { null }
    private fun EditText.required(name: String): String = valueOrNull() ?: throw IllegalArgumentException("$name is required.")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun decimalInput(): Int = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private fun money(code: String, decimal: String): Money {
        val canonicalCode = code.trim().uppercase(Locale.US)
        val fractionDigits = Currency.getInstance(canonicalCode).defaultFractionDigits
        require(fractionDigits >= 0) { "$canonicalCode does not define a standard minor unit." }
        return Money(
            canonicalCode,
            BigDecimal(decimal).movePointRight(fractionDigits)
                .setScale(0, RoundingMode.UNNECESSARY).longValueExact(),
        )
    }
    private fun moneyDecimal(value: Money): String {
        val fractionDigits = Currency.getInstance(value.currencyCode).defaultFractionDigits
        return BigDecimal.valueOf(value.minorUnits, fractionDigits).stripTrailingZeros().toPlainString()
    }
    private fun formatOdometer(value: OdometerReading): String = "${value.value} ${if (value.unit == DistanceUnit.MILES) "mi" else "km"}"
    private fun eventLabel(value: MaintenanceEventType): String = if (value == MaintenanceEventType.OTHER) "Note / other" else enumLabel(value)
    private fun enumLabel(value: Enum<*>): String = value.name.lowercase(Locale.US).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.US) }

    private data class LineItemInputs(
        val id: String, val type: Spinner, val description: EditText, val maker: EditText,
        val part: EditText, val spec: EditText, val quantity: EditText, val unit: EditText,
        val currency: EditText, val cost: EditText, val root: View,
    )
    private data class MeasurementInputs(
        val id: String, val name: EditText, val type: Spinner, val value: EditText,
        val unit: EditText, val method: EditText, val grade: EditText, val root: View,
    )
    private data class CustomFieldInputs(
        val id: EditText, val label: EditText, val type: Spinner, val value: EditText,
        val unit: EditText, val root: View,
    )

    private data class PendingAttachmentTarget(
        val vehicleId: String,
        val recordId: String,
        val revisionId: String,
    )

    companion object {
        private const val ATTACHMENT_REQUEST = 4_201
        private const val MAINTENANCE_ARCHIVE_EXPORT_REQUEST = 4_202
        private const val STATE_ATTACHMENT_VEHICLE_ID = "maintenance.attachment.vehicle_id"
        private const val STATE_ATTACHMENT_RECORD_ID = "maintenance.attachment.record_id"
        private const val STATE_ATTACHMENT_REVISION_ID = "maintenance.attachment.revision_id"
        private const val STATE_ARCHIVE_VEHICLE_ID = "maintenance.archive.vehicle_id"
    }
}

internal fun maintenanceEventLabel(value: MaintenanceEventType): String =
    if (value == MaintenanceEventType.OTHER) {
        "Note / other"
    } else {
        value.name.lowercase(Locale.US).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.US) }
    }

internal fun maintenanceRecordListLabel(record: MaintenanceRecordRevision): String = buildString {
    append(maintenanceEventLabel(record.eventType)).append("  •  ").append(record.title)
    append("\n").append(record.occurredAt.take(10))
    record.odometer?.let {
        append("  •  ").append(it.value).append(if (it.unit == DistanceUnit.MILES) " mi" else " km")
    }
    if (record.state == MaintenanceRecordState.VOIDED) append("  •  VOIDED")
}
