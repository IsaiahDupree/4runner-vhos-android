package dev.vhos.headunit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
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
import dev.vhos.maintenance.ConfigurationAttributeSource
import dev.vhos.maintenance.ConfigurationAttributeState
import dev.vhos.maintenance.ConfigurationResolutionStatus
import dev.vhos.maintenance.DistanceUnit
import dev.vhos.maintenance.MaintenanceApplicabilitySnapshot
import dev.vhos.maintenance.MaintenanceApplicabilityStatus
import dev.vhos.maintenance.MaintenanceDueProjection
import dev.vhos.maintenance.MaintenanceDueState
import dev.vhos.maintenance.MaintenanceIds
import dev.vhos.maintenance.MaintenanceInterval
import dev.vhos.maintenance.MaintenanceRequirementAuthority
import dev.vhos.maintenance.MaintenanceRequirementRevision
import dev.vhos.maintenance.MaintenanceRequirementState
import dev.vhos.maintenance.MaintenanceSystemRef
import dev.vhos.maintenance.MaintenanceTask
import dev.vhos.maintenance.OdometerReading
import dev.vhos.maintenance.SevereUseCondition
import dev.vhos.maintenance.SevereUseSource
import dev.vhos.maintenance.SevereUseStatus
import dev.vhos.maintenance.VehicleAsset
import dev.vhos.maintenance.VehicleConfigurationAttribute
import dev.vhos.maintenance.VehicleConfigurationSnapshot
import dev.vhos.store.EvidenceDatabase
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owner-facing maintenance planning and vehicle applicability editor.
 *
 * All writes append immutable revisions to the encrypted local ledger. The screen never promotes
 * an owner rule to OEM authority, never edits a verified pack rule, and never guesses a due state
 * when configuration, applicability, counters, or an explicit completion baseline is missing.
 */
@SuppressLint("SetTextI18n")
class MaintenancePlanActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    @Volatile private var generation = 0
    @Volatile private var database: EvidenceDatabase? = null

    private lateinit var status: TextView
    private lateinit var configurationList: LinearLayout
    private lateinit var severeUseList: LinearLayout
    private lateinit var requirementsList: LinearLayout
    private lateinit var addRuleButton: Button

    private var requestedVehicleId: String? = null
    private var vehicle: VehicleAsset? = null
    private var requirements: List<MaintenanceRequirementRevision> = emptyList()
    private var projections: Map<String, MaintenanceDueProjection> = emptyMap()
    private var projectionError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedVehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID)
        setContentView(buildRoot())
        reload()
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        worker.shutdownNow()
        super.onDestroy()
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
            addView(title("MAINTENANCE PLAN & APPLICABILITY"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(actionButton("Refresh", ::reload))
            addView(actionButton("Back to Garage") { finish() })
        })
        status = TextView(this).apply {
            text = "Opening encrypted maintenance plan…"
            textSize = 14f
            setTextColor(getColor(R.color.vhos_muted))
            setBackgroundColor(getColor(R.color.vhos_surface))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val panes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val vehiclePane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12), dp(12))
        }
        vehiclePane.addView(sectionTitle("Vehicle configuration"))
        vehiclePane.addView(bodyText(
            "These facts decide whether a maintenance rule applies. Unknown stays unknown; " +
                "changing configuration invalidates any prior verified-pack match until it is re-evaluated.",
        ))
        vehiclePane.addView(actionButton("Add or revise attribute") {
            vehicle?.let { showConfigurationDialog(it, null) }
                ?: toast("A vehicle asset must load before it can be revised.")
        })
        configurationList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vehiclePane.addView(configurationList)
        vehiclePane.addView(sectionTitle("Severe-use conditions"))
        vehiclePane.addView(bodyText(
            "YES / NO / UNKNOWN are evidence states. They are not inferred from missing data.",
        ))
        vehiclePane.addView(actionButton("Add or revise condition") {
            vehicle?.let { showSevereUseDialog(it, null) }
                ?: toast("A vehicle asset must load before it can be revised.")
        })
        severeUseList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vehiclePane.addView(severeUseList)
        panes.addView(ScrollView(this).apply { addView(vehiclePane) }, LinearLayout.LayoutParams(0, -1, .42f))

        val requirementPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, dp(12))
        }
        requirementPane.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle("Maintenance requirements"), LinearLayout.LayoutParams(0, -2, 1f))
            addRuleButton = actionButton("New owner rule") {
                vehicle?.let { showOwnerRuleDialog(it, null) }
                    ?: toast("A vehicle asset must load before a rule can be created.")
            }
            addView(addRuleButton)
        })
        requirementPane.addView(bodyText(
            "Owner rules are editable only by appending revisions. Verified Vehicle Pack rules are " +
                "read-only. Every due result shows its evidence and remains UNKNOWN when inputs are incomplete.",
        ))
        requirementsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        requirementPane.addView(requirementsList)
        panes.addView(ScrollView(this).apply { addView(requirementPane) }, LinearLayout.LayoutParams(0, -1, .58f))

        root.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        addRuleButton.isEnabled = false
        render()
        return root
    }

    private fun reload() {
        val request = ++generation
        statusLine("Loading current vehicle revision, requirements, and due evidence…", R.color.vhos_accent)
        worker.execute {
            try {
                val db = database ?: EvidenceDatabase.open(applicationContext).also { database = it }
                val loadedVehicle = requestedVehicleId?.let(db::currentVehicleAsset)
                    ?: db.currentVehicleAssets().firstOrNull()
                    ?: throw IllegalStateException("No vehicle asset exists. Create one in Maintenance Garage first.")
                requestedVehicleId = loadedVehicle.vehicleId
                val loadedRequirements = db.currentMaintenanceRequirements(loadedVehicle.vehicleId)
                val dueResult = runCatching {
                    db.maintenanceDueProjections(loadedVehicle.vehicleId).associateBy { it.requirementId }
                }
                runOnUiThread {
                    if (!isCurrent(request)) return@runOnUiThread
                    vehicle = loadedVehicle
                    requirements = loadedRequirements
                    projections = dueResult.getOrDefault(emptyMap())
                    projectionError = dueResult.exceptionOrNull()?.let(::safeMessage)
                    render()
                    if (projectionError == null) {
                        statusLine(
                            "${loadedVehicle.displayName}: ${loadedRequirements.size} active requirement(s). " +
                                "Due state is evidence-derived and fails closed.",
                            R.color.vhos_pass,
                        )
                    } else {
                        statusLine(
                            "Requirements loaded, but due calculation is unavailable: $projectionError",
                            R.color.vhos_check,
                        )
                    }
                }
            } catch (error: RuntimeException) {
                runOnUiThread {
                    if (!isCurrent(request)) return@runOnUiThread
                    vehicle = null
                    requirements = emptyList()
                    projections = emptyMap()
                    projectionError = safeMessage(error)
                    render()
                    statusLine("Maintenance plan unavailable: ${safeMessage(error)}", R.color.vhos_blocked)
                }
            }
        }
    }

    private fun render() {
        renderConfiguration()
        renderSevereUse()
        renderRequirements()
        if (::addRuleButton.isInitialized) addRuleButton.isEnabled = vehicle != null
    }

    private fun renderConfiguration() {
        if (!::configurationList.isInitialized) return
        configurationList.removeAllViews()
        val asset = vehicle
        if (asset == null) {
            configurationList.addView(emptyCard("No current vehicle revision is available."))
            return
        }
        configurationList.addView(infoCard(buildString {
            append(asset.displayName).append("\n")
            append(asset.modelYear).append(' ').append(asset.make).append(' ').append(asset.model)
            asset.trim?.let { append(" • ").append(it) }
            append("\nRevision: ").append(asset.revisionId)
            append("\nConfiguration: ").append(enumLabel(asset.configuration.resolutionStatus))
            append("\nVerified pack: ")
            val pack = asset.activeVehiclePack
            if (pack == null) {
                append("none active")
            } else {
                append(pack.packId).append(" @ ").append(pack.packVersion)
                append("\nManifest SHA-256: ").append(pack.sourceManifestSha256)
            }
        }))
        if (asset.configuration.attributes.isEmpty()) {
            configurationList.addView(emptyCard(
                "No configuration facts are recorded. Vehicle-specific rule applicability must remain unresolved.",
            ))
        } else {
            asset.configuration.attributes.sortedBy { it.key }.forEach { attribute ->
                configurationList.addView(attributeCard(asset, attribute))
            }
        }
    }

    private fun attributeCard(asset: VehicleAsset, attribute: VehicleConfigurationAttribute): View =
        cardLayout().apply {
            addView(bodyText(buildString {
                append(attribute.key).append("  •  ").append(enumLabel(attribute.state)).append('\n')
                attribute.value?.let { append(it).append('\n') }
                append("Source: ").append(enumLabel(attribute.source))
                attribute.observedAt?.let { append("\nObserved: ").append(it) }
            }), LinearLayout.LayoutParams(0, -2, 1f))
            addView(actionButton("Revise") { showConfigurationDialog(asset, attribute) })
        }

    private fun renderSevereUse() {
        if (!::severeUseList.isInitialized) return
        severeUseList.removeAllViews()
        val asset = vehicle
        if (asset == null || asset.severeUseConditions.isEmpty()) {
            severeUseList.addView(emptyCard(
                "No severe-use evidence is recorded. Schedule selection must not assume normal or severe use.",
            ))
            return
        }
        asset.severeUseConditions.sortedBy { it.conditionKey }.forEach { condition ->
            severeUseList.addView(cardLayout().apply {
                addView(bodyText(buildString {
                    append(condition.conditionKey).append("  •  ").append(enumLabel(condition.status)).append('\n')
                    append("Source: ").append(enumLabel(condition.source))
                    condition.observedAt?.let { append("\nObserved: ").append(it) }
                    condition.notes?.let { append("\n").append(it) }
                }), LinearLayout.LayoutParams(0, -2, 1f))
                addView(actionButton("Revise") { showSevereUseDialog(asset, condition) })
            })
        }
    }

    private fun renderRequirements() {
        if (!::requirementsList.isInitialized) return
        requirementsList.removeAllViews()
        if (requirements.isEmpty()) {
            requirementsList.addView(emptyCard(
                "No active requirements exist. Add an owner rule, or install a signed vehicle-specific pack through the trusted ingestion path.",
            ))
            return
        }
        requirements.sortedWith(compareBy({ it.authority.name }, { it.task.title.lowercase(Locale.US) }))
            .forEach { requirement -> requirementsList.addView(requirementCard(requirement)) }
    }

    private fun requirementCard(requirement: MaintenanceRequirementRevision): View {
        val due = projections[requirement.requirementId]
        val owner = requirement.authority == MaintenanceRequirementAuthority.OWNER_CUSTOM
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(getColor(R.color.vhos_surface))
            addView(TextView(this@MaintenancePlanActivity).apply {
                text = requirement.task.title
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(dueColor(due?.state)))
            })
            addView(bodyText(buildString {
                append(if (owner) "OWNER CUSTOM" else "VERIFIED VEHICLE PACK")
                append("  •  ").append(due?.state?.let(::enumLabel) ?: "Unknown")
                append("\nSystem: ")
                append(requirement.task.system.displayName ?: "Unknown")
                requirement.task.system.systemId?.let { append(" (").append(it).append(')') }
                append("\nInterval: ").append(intervalText(requirement.interval))
                append("\nApplicability: ").append(enumLabel(requirement.applicability.status))
                append(" — ").append(requirement.applicability.rationale)
                if (requirement.applicability.requiredConfigurationKeys.isNotEmpty()) {
                    append("\nRequired facts: ")
                    append(requirement.applicability.requiredConfigurationKeys.joinToString())
                }
                if (requirement.applicability.unresolvedKeys.isNotEmpty()) {
                    append("\nUnresolved facts: ")
                    append(requirement.applicability.unresolvedKeys.joinToString())
                }
                append("\nDue evidence: ")
                append(due?.explanation ?: projectionError?.let { "calculation unavailable ($it)" }
                    ?: "no projection was returned; state remains UNKNOWN")
                append("\nSource: ").append(sourceText(requirement))
                requirement.notes?.let { append("\nNotes: ").append(it) }
                append("\nTask: ").append(requirement.task.taskId)
                append("\nRevision: ").append(requirement.requirementId)
                append("\nVehicle revision: ").append(requirement.vehicleAssetRevisionId)
            }))
            if (owner) {
                addView(LinearLayout(this@MaintenancePlanActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(actionButton("Amend / re-evaluate") {
                        val asset = vehicle
                        if (asset == null) toast("Reload the current vehicle first.")
                        else showOwnerRuleDialog(asset, requirement)
                    })
                    addView(actionButton("Void") { showVoidRuleDialog(requirement) })
                })
            } else {
                addView(TextView(this@MaintenancePlanActivity).apply {
                    text = "READ-ONLY — signed pack rules can only change through verified pack ingestion."
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.vhos_pass))
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
    }

    private fun showConfigurationDialog(
        asset: VehicleAsset,
        existing: VehicleConfigurationAttribute?,
    ) {
        val form = formLayout()
        val key = form.field("Canonical configuration key", existing?.key, "example: powertrain.engine_family")
        val state = form.spinner(
            "Evidence state",
            ConfigurationAttributeState.entries.map(::enumLabel),
            existing?.state?.ordinal ?: ConfigurationAttributeState.UNKNOWN.ordinal,
        )
        val value = form.field("Value (required only when KNOWN)", existing?.value, "e.g. 2UZ-FE")
        val source = form.spinner(
            "Evidence source",
            ConfigurationAttributeSource.entries.map(::enumLabel),
            existing?.source?.ordinal ?: ConfigurationAttributeSource.MANUAL.ordinal,
        )
        showValidatedDialog(
            if (existing == null) "Add configuration evidence" else "Append configuration revision",
            form.root,
            "Append revision",
        ) { dismissOnSuccess, retainForRetry ->
            val selectedState = ConfigurationAttributeState.entries[state.selectedItemPosition]
            val attribute = VehicleConfigurationAttribute(
                key = key.required("Configuration key"),
                state = selectedState,
                value = if (selectedState == ConfigurationAttributeState.KNOWN) {
                    value.required("Known configuration value")
                } else null,
                source = ConfigurationAttributeSource.entries[source.selectedItemPosition],
                observedAt = Instant.now().toString(),
            ).validate()
            val attributes = asset.configuration.attributes.filterNot {
                it.key == attribute.key || it.key == existing?.key
            } + attribute
            val next = asset.copy(
                revisionId = MaintenanceIds.vehicleRevision(),
                supersedesRevisionId = asset.revisionId,
                createdAt = Instant.now().toString(),
                configuration = VehicleConfigurationSnapshot(
                    schemaVersion = asset.configuration.schemaVersion,
                    resolutionStatus = maintenanceConfigurationResolution(attributes),
                    attributes = attributes.sortedBy { it.key },
                ).validate(),
                activeVehiclePack = null,
            ).validate()
            confirmPackInvalidation(
                asset = asset,
                continueWrite = {
                    appendVehicleRevision(
                        next = next,
                        success = "Configuration evidence appended",
                        onSuccess = dismissOnSuccess,
                        onFailure = retainForRetry,
                    )
                },
                onCancel = retainForRetry,
            )
        }
    }

    private fun showSevereUseDialog(asset: VehicleAsset, existing: SevereUseCondition?) {
        val form = formLayout()
        val key = form.field("Canonical condition key", existing?.conditionKey, "example: repeated_short_trips")
        val state = form.spinner(
            "Status",
            SevereUseStatus.entries.map(::enumLabel),
            existing?.status?.ordinal ?: SevereUseStatus.UNKNOWN.ordinal,
        )
        val source = form.spinner(
            "Evidence source",
            SevereUseSource.entries.map(::enumLabel),
            existing?.source?.ordinal ?: SevereUseSource.OWNER.ordinal,
        )
        val notes = form.field("Notes (optional)", existing?.notes, "Observed conditions or evidence", multiline = true)
        showValidatedDialog(
            if (existing == null) "Add severe-use evidence" else "Append severe-use revision",
            form.root,
            "Append revision",
        ) { dismissOnSuccess, retainForRetry ->
            val condition = SevereUseCondition(
                conditionKey = key.required("Condition key"),
                status = SevereUseStatus.entries[state.selectedItemPosition],
                source = SevereUseSource.entries[source.selectedItemPosition],
                observedAt = Instant.now().toString(),
                notes = notes.valueOrNull(),
            ).validate()
            val conditions = asset.severeUseConditions.filterNot {
                it.conditionKey == condition.conditionKey || it.conditionKey == existing?.conditionKey
            } + condition
            val next = asset.copy(
                revisionId = MaintenanceIds.vehicleRevision(),
                supersedesRevisionId = asset.revisionId,
                createdAt = Instant.now().toString(),
                severeUseConditions = conditions.sortedBy { it.conditionKey },
                activeVehiclePack = null,
            ).validate()
            confirmPackInvalidation(
                asset = asset,
                continueWrite = {
                    appendVehicleRevision(
                        next = next,
                        success = "Severe-use evidence appended",
                        onSuccess = dismissOnSuccess,
                        onFailure = retainForRetry,
                    )
                },
                onCancel = retainForRetry,
            )
        }
    }

    private fun showOwnerRuleDialog(
        asset: VehicleAsset,
        existing: MaintenanceRequirementRevision?,
    ) {
        require(existing == null || existing.authority == MaintenanceRequirementAuthority.OWNER_CUSTOM)
        val form = formLayout()
        val title = form.field("Task title", existing?.task?.title, "e.g. Replace engine oil and filter")
        val systemKey = form.field(
            "Canonical system key",
            existing?.task?.system?.systemId,
            "e.g. engine.lubrication",
        )
        val systemName = form.field(
            "System display name",
            existing?.task?.system?.displayName,
            "e.g. Engine lubrication",
        )
        val distance = form.field(
            "Distance interval (${distanceLabel(asset.distanceUnit)}; optional)",
            existing?.interval?.distance?.value?.toString(),
            "positive whole number",
            numeric = true,
        )
        val distanceWarning = form.field(
            "Upcoming distance window (optional)",
            existing?.interval?.upcomingDistance?.value?.toString(),
            "must be less than interval",
            numeric = true,
        )
        val months = form.field(
            "Calendar interval in months (optional)",
            existing?.interval?.months?.toString(),
            "positive whole number",
            numeric = true,
        )
        val monthsWarning = form.field(
            "Upcoming calendar window in months (optional)",
            existing?.interval?.upcomingMonths?.toString(),
            "must be less than interval",
            numeric = true,
        )
        val hours = form.field(
            "Engine-hour interval (optional)",
            existing?.interval?.engineHours,
            "positive decimal",
            decimal = true,
        )
        val hoursWarning = form.field(
            "Upcoming engine-hour window (optional)",
            existing?.interval?.upcomingEngineHours,
            "must be less than interval",
            decimal = true,
        )
        val notes = form.field("Rule notes (optional)", existing?.notes, "Owner context", multiline = true)
        val reason = if (existing != null) {
            form.field(
                "Amendment / re-evaluation reason",
                null,
                "Required; explain what changed",
                multiline = true,
            )
        } else null
        showValidatedDialog(
            if (existing == null) "Create owner maintenance rule" else "Append owner-rule revision",
            form.root,
            if (existing == null) "Create rule" else "Append amendment",
        ) { dismissOnSuccess, retainForRetry ->
            val distanceValue = distance.optionalLong("Distance interval")
            val distanceWarningValue = distanceWarning.optionalLong("Upcoming distance window")
            val monthsValue = months.optionalInt("Calendar interval")
            val monthsWarningValue = monthsWarning.optionalInt("Upcoming calendar window")
            val hoursValue = hours.valueOrNull()?.let(::canonicalMaintenancePlanDecimal)
            val hoursWarningValue = hoursWarning.valueOrNull()?.let(::canonicalMaintenancePlanDecimal)
            val interval = MaintenanceInterval(
                distance = distanceValue?.let { OdometerReading(it, asset.distanceUnit) },
                months = monthsValue,
                engineHours = hoursValue,
                upcomingDistance = distanceWarningValue?.let { OdometerReading(it, asset.distanceUnit) },
                upcomingMonths = monthsWarningValue,
                upcomingEngineHours = hoursWarningValue,
            ).validate()
            val now = Instant.now().toString()
            val requirement = MaintenanceRequirementRevision(
                requirementId = MaintenanceIds.requirement(),
                supersedesRequirementId = existing?.requirementId,
                task = MaintenanceTask(
                    taskId = existing?.task?.taskId ?: MaintenanceIds.maintenanceTask(),
                    title = title.required("Task title"),
                    system = MaintenanceSystemRef(
                        systemId = systemKey.required("System key"),
                        displayName = systemName.required("System display name"),
                    ),
                    components = existing?.task?.components ?: emptyList(),
                ),
                vehicleId = asset.vehicleId,
                vehicleAssetRevisionId = asset.revisionId,
                authority = MaintenanceRequirementAuthority.OWNER_CUSTOM,
                state = MaintenanceRequirementState.ACTIVE,
                interval = interval,
                applicability = MaintenanceApplicabilitySnapshot(
                    status = MaintenanceApplicabilityStatus.MATCHED,
                    vehicleRevisionId = asset.revisionId,
                    evaluatedAt = now,
                    rationale = "Owner-defined requirement explicitly applies to this vehicle revision.",
                ),
                notes = notes.valueOrNull(),
                actor = LocalMaintenanceIdentity.owner(this),
                createdAt = now,
                amendmentReason = reason?.required("Amendment reason"),
            ).validate()
            submitMutation(
                if (existing == null) "Creating owner rule…" else "Appending owner-rule amendment…",
                if (existing == null) "Owner rule created" else "Owner rule amended and re-evaluated",
                onSuccess = dismissOnSuccess,
                onFailure = retainForRetry,
            ) { db ->
                if (existing == null) db.createOwnerMaintenanceRequirement(requirement)
                else db.amendOwnerMaintenanceRequirement(requirement)
            }
        }
    }

    private fun showVoidRuleDialog(requirement: MaintenanceRequirementRevision) {
        require(requirement.authority == MaintenanceRequirementAuthority.OWNER_CUSTOM)
        val form = formLayout()
        val reason = form.field(
            "Why is this owner rule being voided?",
            null,
            "Required; history remains permanent",
            multiline = true,
        )
        showValidatedDialog("Void owner rule", form.root, "Append void revision") {
                dismissOnSuccess, retainForRetry ->
            val value = reason.required("Void reason")
            submitMutation(
                progress = "Voiding owner rule…",
                success = "Owner rule voided",
                onSuccess = dismissOnSuccess,
                onFailure = retainForRetry,
            ) { db ->
                db.voidOwnerMaintenanceRequirement(
                    requirement.vehicleId,
                    requirement.task.taskId,
                    LocalMaintenanceIdentity.owner(this),
                    value,
                )
            }
        }
    }

    private fun appendVehicleRevision(
        next: VehicleAsset,
        success: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        submitMutation(
            progress = "Appending vehicle-configuration revision…",
            success = success,
            onSuccess = onSuccess,
            onFailure = onFailure,
        ) { db ->
            db.appendVehicleAsset(next)
        }
    }

    private fun confirmPackInvalidation(
        asset: VehicleAsset,
        continueWrite: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val activePack = asset.activeVehiclePack
        if (activePack == null) {
            continueWrite()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Verified applicability must be re-evaluated")
            .setMessage(
                "This vehicle revision is currently matched to ${activePack.packId} " +
                    "@ ${activePack.packVersion}. The new facts will clear that active match. " +
                    "Verified requirements remain visible but must resolve UNKNOWN until trusted pack ingestion " +
                    "re-evaluates the exact new vehicle revision.",
            )
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setPositiveButton("Append and invalidate") { _, _ ->
                try {
                    continueWrite()
                } catch (error: RuntimeException) {
                    onCancel()
                    toast(safeMessage(error))
                }
            }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun submitMutation(
        progress: String,
        success: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        operation: (EvidenceDatabase) -> Unit,
    ) {
        statusLine(progress, R.color.vhos_accent)
        try {
            worker.execute {
                try {
                    val db = database ?: EvidenceDatabase.open(applicationContext).also { database = it }
                    operation(db)
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        onSuccess()
                        toast(success)
                        reload()
                    }
                } catch (error: RuntimeException) {
                    runOnUiThread {
                        if (!destroyed) {
                            onFailure()
                            val message = "Write rejected: ${safeMessage(error)}"
                            statusLine(message, R.color.vhos_blocked)
                            toast(message)
                        }
                    }
                }
            }
        } catch (error: RuntimeException) {
            onFailure()
            val message = "Write could not start: ${safeMessage(error)}"
            statusLine(message, R.color.vhos_blocked)
            toast(message)
        }
    }

    private fun showValidatedDialog(
        title: String,
        content: View,
        positiveLabel: String,
        onSubmit: (dismissOnSuccess: () -> Unit, retainForRetry: () -> Unit) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positiveLabel, null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                positive.isEnabled = false
                val retainForRetry = {
                    if (dialog.isShowing) positive.isEnabled = true
                }
                try {
                    onSubmit(
                        { if (dialog.isShowing) dialog.dismiss() },
                        retainForRetry,
                    )
                } catch (error: RuntimeException) {
                    retainForRetry()
                    toast(safeMessage(error))
                }
            }
        }
        dialog.show()
    }

    private fun sourceText(requirement: MaintenanceRequirementRevision): String = when (requirement.authority) {
        MaintenanceRequirementAuthority.OWNER_CUSTOM -> "Owner-authored; no OEM authority claimed"
        MaintenanceRequirementAuthority.VERIFIED_VEHICLE_PACK -> {
            val rule = requirement.verifiedRule
            if (rule == null) {
                "Invalid verified lineage — unavailable"
            } else {
                "${rule.packId} @ ${rule.packVersion}; ${rule.sourceLocator.sectionLabel}; " +
                    "PDF pages ${rule.sourceLocator.pdfPageStart}–${rule.sourceLocator.pdfPageEnd}; " +
                    "manifest ${rule.sourceManifestSha256}"
            }
        }
    }

    private fun intervalText(interval: MaintenanceInterval): String = buildList {
        interval.distance?.let { add("every ${it.value} ${distanceLabel(it.unit)}") }
        interval.months?.let { add("every $it months") }
        interval.engineHours?.let { add("every $it engine hours") }
    }.joinToString("; ")

    private fun dueColor(state: MaintenanceDueState?): Int = when (state) {
        MaintenanceDueState.CURRENT -> R.color.vhos_pass
        MaintenanceDueState.UPCOMING, MaintenanceDueState.DUE -> R.color.vhos_check
        MaintenanceDueState.OVERDUE -> R.color.vhos_blocked
        MaintenanceDueState.UNKNOWN, MaintenanceDueState.NOT_APPLICABLE, null -> R.color.vhos_muted
    }

    private fun infoCard(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(getColor(R.color.vhos_text))
        setBackgroundColor(getColor(R.color.vhos_surface))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }

    private fun emptyCard(text: String): TextView = infoCard(text).apply {
        setTextColor(getColor(R.color.vhos_muted))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(getColor(R.color.vhos_surface))
        setPadding(dp(12), dp(6), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    }

    private fun formLayout() = Form(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(16))
    })

    private inner class Form(val root: LinearLayout) {
        fun field(
            label: String,
            value: String? = null,
            hint: String,
            multiline: Boolean = false,
            numeric: Boolean = false,
            decimal: Boolean = false,
        ): EditText {
            root.addView(label(label))
            return input(hint).apply {
                inputType = when {
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT
                }
                if (multiline) minLines = 3 else setSingleLine(true)
                setText(value.orEmpty())
                root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }

        fun spinner(label: String, values: List<String>, selected: Int): Spinner {
            root.addView(label(label))
            return Spinner(this@MaintenancePlanActivity).apply {
                adapter = textAdapter(values)
                setSelection(selected)
                root.addView(this, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
            }
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.vhos_text))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.vhos_text))
        setPadding(0, dp(6), 0, dp(5))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.vhos_muted))
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setLineSpacing(0f, 1.12f)
        setTextColor(getColor(R.color.vhos_text))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(getColor(R.color.vhos_text))
        setHintTextColor(getColor(R.color.vhos_muted))
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(getColor(R.color.vhos_text))
        setOnClickListener { action() }
    }

    private fun textAdapter(values: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun EditText.valueOrNull(): String? = text.toString().trim().ifBlank { null }
    private fun EditText.required(name: String): String = valueOrNull()
        ?: throw IllegalArgumentException("$name is required.")
    private fun EditText.optionalLong(name: String): Long? = valueOrNull()?.let { raw ->
        raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("$name must be a positive whole number.")
    }
    private fun EditText.optionalInt(name: String): Int? = optionalLong(name)?.let { value ->
        require(value <= Int.MAX_VALUE) { "$name is too large." }
        value.toInt()
    }

    private fun isCurrent(request: Int): Boolean = !destroyed && request == generation
    private fun statusLine(message: String, color: Int) {
        if (!destroyed && ::status.isInitialized) {
            status.text = message
            status.setTextColor(getColor(color))
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    private fun enumLabel(value: Enum<*>): String = value.name.lowercase(Locale.US)
        .replace('_', ' ')
        .replaceFirstChar { it.titlecase(Locale.US) }
    private fun distanceLabel(unit: DistanceUnit): String =
        if (unit == DistanceUnit.MILES) "miles" else "kilometers"
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VEHICLE_ID = "dev.vhos.headunit.extra.MAINTENANCE_VEHICLE_ID"
        fun intent(context: Context, vehicleId: String): Intent =
            Intent(context, MaintenancePlanActivity::class.java)
                .putExtra(EXTRA_VEHICLE_ID, vehicleId)
    }
}

internal fun maintenanceConfigurationResolution(
    attributes: List<VehicleConfigurationAttribute>,
): ConfigurationResolutionStatus = when {
    attributes.isEmpty() -> ConfigurationResolutionStatus.UNKNOWN
    attributes.none { it.state == ConfigurationAttributeState.KNOWN } &&
        attributes.any { it.state == ConfigurationAttributeState.UNKNOWN } ->
        ConfigurationResolutionStatus.UNKNOWN
    attributes.any { it.state == ConfigurationAttributeState.UNKNOWN } ->
        ConfigurationResolutionStatus.PARTIAL
    else -> ConfigurationResolutionStatus.RESOLVED
}

internal fun canonicalMaintenancePlanDecimal(raw: String): String {
    val decimal = try {
        BigDecimal(raw)
    } catch (error: NumberFormatException) {
        throw IllegalArgumentException("Engine hours must be a decimal.", error)
    }
    require(decimal > BigDecimal.ZERO) { "Engine hours must be greater than zero." }
    return decimal.stripTrailingZeros().toPlainString()
}
