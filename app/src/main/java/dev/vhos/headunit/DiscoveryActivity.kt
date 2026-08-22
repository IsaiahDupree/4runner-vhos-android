package dev.vhos.headunit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.vhos.discovery.AndroidCandidateResearchAdapter
import dev.vhos.discovery.AndroidCandidateResearchItem
import dev.vhos.discovery.AndroidCaptureFinalizationAuthority
import dev.vhos.discovery.AndroidCaptureDraftState
import dev.vhos.discovery.AndroidDiscoveryCaptureDraft
import dev.vhos.discovery.AndroidDiscoveryEngineeringGate
import dev.vhos.discovery.AndroidDiscoveryEngineeringSafetyGate
import dev.vhos.discovery.AndroidDiscoveryExecutionAuthority
import dev.vhos.discovery.AndroidDiscoveryMarkerDefinition
import dev.vhos.discovery.AndroidDiscoveryMarkerKind
import dev.vhos.discovery.AndroidDiscoveryMarkerRecord
import dev.vhos.discovery.AndroidDiscoveryLiveEvidence
import dev.vhos.discovery.AndroidDiscoverySafetyEvidence
import dev.vhos.discovery.AndroidDiscoveryTestLibrary
import dev.vhos.discovery.AndroidDiscoveryTestTemplate
import dev.vhos.discovery.AndroidVehicleCapabilityObservation
import dev.vhos.discovery.CanDiscoveryAnalyzer
import dev.vhos.discovery.CanDiscoveryReport
import dev.vhos.discovery.DiscoveryObservation
import dev.vhos.discovery.HISTORICAL_REPLAY_LABEL
import dev.vhos.discovery.HistoricalCanReplay
import dev.vhos.discovery.HistoricalReplayProgress
import dev.vhos.discovery.HistoricalReplayReport
import dev.vhos.discovery.SignalHypothesisCatalog
import dev.vhos.discovery.SignalHypothesisEvaluator
import dev.vhos.discovery.SignalResearchPlanner
import dev.vhos.model.ConnectionPhase
import dev.vhos.model.HeadUnitSnapshot
import dev.vhos.model.IndicatorLevel
import dev.vhos.store.DiscoveryEvidenceSummary
import dev.vhos.store.DiscoveryEvidenceScope
import dev.vhos.store.EvidenceDatabase
import dev.vhos.store.PersistedAndroidDiscoveryCapture
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Landscape-first Android Engineering workspace. All values originate from the encrypted evidence
 * store or the current validated runtime snapshot. Empty, stale, and unsupported states remain
 * explicit; this activity never manufactures vehicle values.
 */
@SuppressLint(
    "SetTextI18n", // Engineering identifiers and provenance are intentionally rendered verbatim.
    "UsableSpace", // Informational display only; no allocation decision uses this value.
)
class DiscoveryActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var navigation: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var inspector: TextView

    @Volatile private var database: EvidenceDatabase? = null
    @Volatile private var destroyed = false
    @Volatile private var loadGeneration = 0
    @Volatile private var replayGeneration = 0
    @Volatile private var replayRunning = false
    private var observingRuntime = false
    private var visible = false
    private var runtimeRenderScheduled = false
    private var selectedSection = Section.OVERVIEW
    private var selectedTemplateIndex = 0
    private var selectedCandidateIndex = 0
    @Volatile private var runtime = HeadUnitRuntime.snapshot()
    private var workspace = WorkspaceData.loading()
    private var replayState: ReplayState = ReplayState.Idle
    private val bootId: String by lazy {
        File("/proc/sys/kernel/random/boot_id").readText().trim().also {
            require(it.isNotBlank()) { "Android boot identity is unavailable." }
        }
    }
    private val runtimeRender = Runnable {
        runtimeRenderScheduled = false
        if (visible && !destroyed) render()
    }
    private val runtimeObserver: (HeadUnitSnapshot) -> Unit = { snapshot ->
        runtime = snapshot
        scheduleRuntimeRender()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        refreshWorkspace()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        runtime = HeadUnitRuntime.snapshot()
        if (!observingRuntime) {
            observingRuntime = true
            HeadUnitRuntime.observe(runtimeObserver)
        }
        render()
    }

    override fun onStop() {
        visible = false
        if (observingRuntime) {
            HeadUnitRuntime.removeObserver(runtimeObserver)
            observingRuntime = false
        }
        if (::status.isInitialized) status.removeCallbacks(runtimeRender)
        runtimeRenderScheduled = false
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        replayRunning = false
        replayGeneration++
        if (observingRuntime) {
            HeadUnitRuntime.removeObserver(runtimeObserver)
            observingRuntime = false
        }
        super.onDestroy()
    }

    private fun scheduleRuntimeRender() = runOnUiThread {
        if (!visible || destroyed || runtimeRenderScheduled || !::status.isInitialized) return@runOnUiThread
        runtimeRenderScheduled = true
        status.postDelayed(runtimeRender, RUNTIME_RENDER_INTERVAL_MILLIS)
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(getColor(R.color.vhos_background))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "VHOS DISCOVERY"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.vhos_text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("Refresh", true, ::refreshWorkspace))
        header.addView(actionButton("Vehicle Health", true) { finish() })
        root.addView(header)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(getColor(R.color.vhos_surface))
        }
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8); bottomMargin = dp(10) })

        val panes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        panes.addView(ScrollView(this).apply { addView(navigation) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.20f))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }
        panes.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.55f))

        inspector = TextView(this).apply {
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setLineSpacing(0f, 1.12f)
            setBackgroundColor(getColor(R.color.vhos_surface))
            setTextColor(getColor(R.color.vhos_text))
        }
        panes.addView(ScrollView(this).apply { addView(inspector) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.25f).apply {
                marginStart = dp(10)
            })
        root.addView(panes, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return root
    }

    private fun refreshWorkspace() {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(::refreshWorkspace)
            return
        }
        val generation = ++loadGeneration
        workspace = WorkspaceData.loading()
        render()
        Thread {
            try {
                val store = EvidenceDatabase.open(applicationContext)
                database = store
                recoverInterruptedCapture(store)
                val scope = store.resolveDiscoveryEvidenceScope(runtime.obd.sourceId)
                val summary = scope?.let(store::discoveryEvidenceSummary)
                    ?: EMPTY_DISCOVERY_SUMMARY
                val persisted = if (summary.canObservations > 0) {
                    store.recentCanObservations(requireNotNull(scope), DISCOVERY_RECORD_LIMIT)
                } else {
                    emptyList()
                }
                val observations = persisted.map { DiscoveryObservation(it.sourceId, it.observation) }
                val report = observations.takeIf(List<DiscoveryObservation>::isNotEmpty)
                    ?.let(CanDiscoveryAnalyzer::analyze)
                val candidateResult = if (report == null) {
                    CandidateResult(emptyList(), null)
                } else {
                    runCatching {
                        val pack = SignalHypothesisCatalog.loadBundled()
                        val evaluation = SignalHypothesisEvaluator.evaluate(observations, pack)
                        val plan = SignalResearchPlanner.plan(report, evaluation, pack)
                        CandidateResult(AndroidCandidateResearchAdapter.from(evaluation, plan), null)
                    }.getOrElse { CandidateResult(emptyList(), it.message ?: it.javaClass.simpleName) }
                }
                val captures = scope?.let { store.recentDiscoveryCaptures(scope = it) }.orEmpty()
                val activeCapture = store.activeDiscoveryCapture()
                val activeMarkers = activeCapture
                    ?.let { store.eventMarkers(it.session.sessionId) }
                    .orEmpty()
                val capabilityObservations = scope
                    ?.let { store.recentAndroidVehicleCapabilityObservations(scope = it) }
                    .orEmpty()
                val next = WorkspaceData(
                    loading = false,
                    scope = scope,
                    summary = summary,
                    report = report,
                    candidates = candidateResult.items,
                    candidateError = candidateResult.error,
                    captures = captures,
                    activeCapture = activeCapture,
                    activeMarkers = activeMarkers,
                    capabilityObservations = capabilityObservations,
                    loadError = null,
                )
                if (!destroyed && generation == loadGeneration) runOnUiThread {
                    workspace = next
                    render()
                }
            } catch (error: Exception) {
                if (!destroyed && generation == loadGeneration) runOnUiThread {
                    workspace = WorkspaceData.failed(error.message ?: error.javaClass.simpleName)
                    render()
                }
            }
        }.start()
    }

    /**
     * Android elapsed-realtime resets on reboot. An unfinished draft from another boot is therefore
     * closed as an interruption before the workspace is loaded. This safety termination does not
     * claim PARKED authority and never promotes or completes the capture.
     */
    private fun recoverInterruptedCapture(store: EvidenceDatabase) {
        val active = store.activeDiscoveryCapture()?.session ?: return
        if (active.startedBootId == bootId) return
        val activeScope = active.evidenceScope()
        val counts = store.evidenceCounts(activeScope)
        store.finalizeDiscoveryCapture(
            active.copy(
                state = AndroidCaptureDraftState.ABORTED,
                endedAt = Instant.now().toString(),
                endedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                endedBootId = bootId,
                endAnchor = store.latestDiscoveryEvidenceAnchor(activeScope),
                endLogicalFrameCount = counts.logicalFrames,
                endCanObservationCount = counts.canObservations,
                finalizationAuthority = AndroidCaptureFinalizationAuthority.INTERRUPTED_BY_REBOOT,
                finalizationSafetyAuthorization = null,
            ).validate()
        )
    }

    private fun render() {
        if (!::status.isInitialized) return
        val parked = parkedGate(runtime)
        status.text = buildString {
            append("GATEWAY ${runtime.obd.phase.displayName.uppercase(Locale.US)}")
            append("  •  EVIDENCE ${workspace.summary?.canObservations ?: runtime.storedCanObservations}")
            append("  •  PARKED AUTHORITY ${if (parked.allowed) "VERIFIED" else "UNKNOWN"}")
            append("  •  ENGINEERING AUTHORITY ONLY")
        }
        status.setTextColor(levelColor(if (workspace.loadError != null) IndicatorLevel.BLOCKED else IndicatorLevel.ACTIVE))
        renderNavigation()
        content.removeAllViews()
        when {
            workspace.loading -> renderLoading()
            workspace.loadError != null -> renderFailure(workspace.loadError.orEmpty())
            selectedSection == Section.OVERVIEW -> renderOverview(parked)
            selectedSection == Section.SIGNALS -> renderSignals()
            selectedSection == Section.TESTS -> renderTests(parked)
            selectedSection == Section.CAPTURES -> renderCaptures(parked)
            selectedSection == Section.CANDIDATES -> renderCandidates()
            selectedSection == Section.REGISTRY -> renderRegistry()
            selectedSection == Section.REPLAY -> renderReplay()
            selectedSection == Section.PROGRESS -> renderProgress()
        }
    }

    private fun renderNavigation() {
        navigation.removeAllViews()
        Section.entries.forEach { section ->
            navigation.addView(Button(this).apply {
                text = section.label
                isAllCaps = false
                textSize = 14f
                isEnabled = section != selectedSection
                setOnClickListener {
                    selectedSection = section
                    render()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(4)
            })
        }
    }

    private fun renderLoading() {
        addTitle("Discovery evidence")
        addCard("VERIFYING ENCRYPTED EVIDENCE STORE\nReading retained CAN, capture protocols, candidates, and capability observations…", IndicatorLevel.ACTIVE)
        inspector.text = "AUTHORITY\nNo cached vehicle value is shown while the local truth store is being verified."
    }

    private fun renderFailure(error: String) {
        addTitle("Discovery unavailable")
        addCard("FAIL-CLOSED\n$error\nNo capture, marker, candidate, or registry state was modified.", IndicatorLevel.BLOCKED)
        addActions(Action("Retry", true, ::refreshWorkspace))
        inspector.text = "AUTHORITY\nDiscovery requires the encrypted evidence store. A store failure never falls back to unencrypted or fabricated data."
    }

    private fun renderOverview(parked: AndroidDiscoveryEngineeringGate) {
        val summary = workspace.summary ?: return
        val report = workspace.report
        val active = activeCapture()
        val liveBus = AndroidDiscoveryLiveEvidence.isVehicleBusCurrent(
            runtime.obd,
            workspace.scope?.sourceId,
            freshnessMillis = LIVE_FRESHNESS_MS,
        )
        addTitle("Discovery overview")
        addCard(buildString {
            appendLine("GATEWAY  ${runtime.obd.phase.displayName.uppercase(Locale.US)}")
            appendLine("VEHICLE SCOPE  ${workspace.scope?.vehicleScopeId?.takeLast(16) ?: "UNRESOLVED"}")
            appendLine("SOURCE  ${workspace.scope?.sourceId ?: "UNRESOLVED"}")
            appendLine("Vehicle bus  ${if (liveBus) "CURRENTLY OBSERVED" else if (summary.canObservations > 0) "HISTORICAL EVIDENCE ONLY" else "UNKNOWN"}")
            appendLine("OBD ECU  ${obdStatus(runtime)}")
            appendLine("Retained observations  ${formatCount(summary.canObservations)}")
            appendLine("Retained sessions  ${summary.canCaptureSessions}")
            appendLine("Unique CAN identifiers  ${if (summary.canObservations > 0) summary.uniqueCanIdentifiers else "UNKNOWN"}")
            appendLine("Observed-rate estimate  ${report?.acquisition?.estimatedObservedRateFps?.let(::decimal)?.plus(" frames/s") ?: "UNKNOWN"}")
            appendLine("Standard OBD values now  ${currentStandardObdReadings(runtime).size}")
            appendLine("Research candidates present  ${workspace.candidates.count { it.retainedRecords > 0 }}")
            appendLine("Current test capture  ${active?.let { it.session.testTemplateSnapshot.title + " • " + workspace.activeMarkers.size + " markers" } ?: "NONE"}")
            append("Encrypted storage free  ${formatBytes(File(filesDir.absolutePath).usableSpace)}")
        }, when {
            liveBus -> IndicatorLevel.PASS
            summary.canObservations > 0 -> IndicatorLevel.CHECK
            else -> IndicatorLevel.WAIT
        })
        addActions(
            Action("Scan vehicle", true, ::startVehicleSession),
            Action(
                "Save capability observation",
                parked.allowed && workspace.scope != null,
                ::saveCapabilityObservation,
            ),
        )
        addActions(
            Action("Explore signals", true) { select(Section.SIGNALS) },
            Action("Review candidates", true) { select(Section.CANDIDATES) },
            Action("Replay saved evidence", summary.canObservations > 0) { select(Section.REPLAY) },
        )
        inspector.text = buildString {
            appendLine("SAFETY GATE")
            appendLine(parked.detail)
            appendLine()
            appendLine("AUTHORITY")
            appendLine("Counts and rates are observed or calculated from retained evidence. They do not establish a vehicle signal meaning or health state.")
            if (workspace.scope == null) {
                appendLine()
                appendLine("SCOPE BLOCKED")
                appendLine("Create a vehicle profile and validate exactly one OBD/CAN source before historical analysis can run.")
            }
            appendLine()
            appendLine("CAPABILITY HISTORY")
            append("${workspace.capabilityObservations.size} Android-internal observations. Portable VehicleCapabilitySnapshot mapping is intentionally not claimed yet.")
        }
    }

    private fun renderSignals() {
        addTitle("Signal Explorer")
        val currentReadings = currentStandardObdReadings(runtime)
        if (currentReadings.isEmpty()) {
            addCard(
                "STANDARD OBD  UNAVAILABLE\nNo current standard value has complete supported-PID evidence in this connection.",
                IndicatorLevel.WAIT,
            )
        } else {
            addCard(buildString {
                appendLine("STANDARD READ-ONLY OBD • CURRENT CONNECTION")
                currentReadings.forEach { reading ->
                    appendLine(
                        "${reading.signalId}  ${decimal(reading.value, 2)} ${reading.unit} • " +
                            "${reading.ecuAddress}/PID ${String.format(Locale.US, "%02X", reading.pid)} • " +
                            "seq ${reading.sourceSequence} • ${reading.observedAt}"
                    )
                }
            }, IndicatorLevel.PASS)
        }
        val activity = workspace.report?.identifierActivity.orEmpty()
        if (activity.isEmpty()) {
            addCard("RAW CAN  NO RETAINED EVIDENCE\nUnknown values remain unknown.", IndicatorLevel.WAIT)
        } else {
            addCard(buildString {
                appendLine(
                    "RAW CAN ACTIVITY • NEWEST " +
                        "${formatCount(workspace.report?.acquisition?.records?.toLong() ?: 0)} RETAINED • NOT LIVE"
                )
                activity.take(24).forEach { item ->
                    appendLine(
                        "${item.identifierHex} • ${item.records} records • ${item.uniquePayloads} payloads • " +
                            "dynamic bytes ${item.dynamicBytePositions.joinToString(prefix = "[", postfix = "]")}"
                    )
                }
            }, IndicatorLevel.CHECK)
        }
        inspector.text = "PROVENANCE\nStandard values retain ECU, PID, definition revision, sequence, gateway monotonic time, and capture identity.\n\nAUTHORITY\nRaw identifiers and candidate fields remain Engineering-only until independent corroboration and golden replay are complete."
    }

    private fun renderTests(parked: AndroidDiscoveryEngineeringGate) {
        val template = selectedTemplate()
        val active = activeCapture()
        addTitle("Test Library  ${selectedTemplateIndex + 1}/${AndroidDiscoveryTestLibrary.templates.size}")
        addCard(buildString {
            appendLine(template.title.uppercase(Locale.US))
            appendLine("${template.category.displayName} • ${template.templateId}@${template.version}")
            appendLine("Execution authority: ${template.executionAuthority}")
            appendLine()
            appendLine(template.purpose)
            appendLine()
            appendLine("PROCEDURE")
            template.instructions.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
            appendLine()
            appendLine("MARKERS")
            template.markers.forEach { marker ->
                appendLine("• ${marker.label}${marker.suggestedUnit?.let { " ($it)" }.orEmpty()}")
            }
        }, IndicatorLevel.CHECK)
        addActions(
            Action("Previous", selectedTemplateIndex > 0) {
                selectedTemplateIndex--
                render()
            },
            Action("Next", selectedTemplateIndex < AndroidDiscoveryTestLibrary.templates.lastIndex) {
                selectedTemplateIndex++
                render()
            },
        )
        val templateAuthorityReady = template.executionAuthority == AndroidDiscoveryExecutionAuthority.PARKED_PASSIVE
        val canBegin = active == null && parked.allowed && templateAuthorityReady && workspace.scope != null
        addActions(Action("Begin immutable capture", canBegin) { beginCapture(template) })
        if (!canBegin) {
            addCard(buildString {
                appendLine("CAPTURE CONTROL LOCKED")
                when {
                    active != null -> append("A capture is already active.")
                    !parked.allowed -> append(parked.detail)
                    workspace.scope == null -> append("Vehicle/source scope is unresolved.")
                    !templateAuthorityReady -> append("This protocol requires ${template.executionAuthority}; that safety workflow is not enabled on this head unit.")
                }
            }, IndicatorLevel.BLOCKED)
        }
        inspector.text = "TEST AUTHORITY\nTemplates are versioned product procedures, not evidence that a signal exists.\n\nMOTION RULE\nCapture/test controls remain disabled unless fresh validated gateway health deterministically proves PARKED. Owner assertion and speed=0 are insufficient."
    }

    private fun renderCaptures(parked: AndroidDiscoveryEngineeringGate) {
        addTitle("Capture Sessions")
        val active = activeCapture()
        if (active != null) {
            val template = active.session.testTemplateSnapshot
            addCard(buildString {
                appendLine("RECORDING • ${template.title}")
                appendLine("Android operational draft ${active.session.sessionId}")
                appendLine("Vehicle ${active.session.vehicleScopeId.takeLast(16)} • source ${active.session.sourceId}")
                appendLine("Started ${active.session.startedAt}")
                appendLine("Authorized by health frame ${active.session.safetyAuthorization.healthFrameSequence}")
                appendLine("Start evidence ${active.session.startCanObservationCount} observations")
                appendLine("Markers ${workspace.activeMarkers.size}")
                append("Current store ${workspace.summary?.canObservations ?: 0} observations")
            }, IndicatorLevel.ACTIVE)
            if (parked.allowed) {
                template.markers.forEach { marker ->
                    addActions(Action("MARK • ${marker.label}", true) { addMarker(marker) })
                }
                addActions(Action("MARK • Custom observation", true, ::showCustomMarkerDialog))
                addActions(
                    Action("Complete capture", true) { finalizeCapture(AndroidCaptureDraftState.COMPLETED) },
                    Action("Abort capture", true) { finalizeCapture(AndroidCaptureDraftState.ABORTED) },
                )
            } else {
                addCard("EVENT CONTROLS LOCKED\n${parked.detail}\nA safety abort remains available because it only terminates recording and cannot promote, complete, or control a vehicle.", IndicatorLevel.BLOCKED)
                addActions(Action("Safety abort", true) { finalizeCapture(AndroidCaptureDraftState.ABORTED) })
            }
        } else {
            addCard("NO ACTIVE CAPTURE\nSelect a parked-passive protocol in Test Library. Controls remain locked until PARKED is deterministically verified.", IndicatorLevel.WAIT)
            addActions(Action("Open Test Library", true) { select(Section.TESTS) })
        }
        if (workspace.captures.isNotEmpty()) {
            addCard(buildString {
                appendLine("RECENT ANDROID OPERATIONAL CAPTURES")
                workspace.captures.take(20).forEach { capture ->
                    val session = capture.session
                    val template = session.testTemplateSnapshot
                    val records = session.endCanObservationCount?.minus(session.startCanObservationCount)
                    appendLine(
                        "${session.state} • ${template.title} • ${session.startedAt} • " +
                            "${records?.let { "$it retained observations" } ?: "recording"} • ${capture.eventMarkerCount} markers"
                    )
                }
            }, IndicatorLevel.CHECK)
        }
        inspector.text = "PERSISTENCE\nStart/end evidence cursors and each marker are stored in SQLCipher. A marker records wall time, Android elapsed-realtime, observer, and nearest retained gateway sequence when one exists.\n\nMAPPING BOUNDARY\nThese are Android operational drafts, not portable finalized CaptureSession records. Archive and manifest hashes are required before cross-platform export."
    }

    private fun renderCandidates() {
        addTitle("Candidate Inbox")
        val error = workspace.candidateError
        if (error != null) {
            addCard("CANDIDATE ANALYSIS BLOCKED\n$error", IndicatorLevel.BLOCKED)
            inspector.text = "FAIL-CLOSED\nThe pinned research pack or its lineage could not be verified."
            return
        }
        if (workspace.candidates.isEmpty()) {
            addCard("NO CANDIDATES\nRetained target evidence is required. No candidate or confidence value is invented.", IndicatorLevel.WAIT)
            inspector.text = "AUTHORITY\nCandidate analysis begins only from retained listen-only evidence."
            return
        }
        selectedCandidateIndex = selectedCandidateIndex.coerceIn(0, workspace.candidates.lastIndex)
        addCard(buildString {
            appendLine("${workspace.candidates.size} RESEARCH ITEMS • PRIORITY IS NOT CONFIDENCE")
            val windowStart = (selectedCandidateIndex - 9).coerceIn(
                0,
                (workspace.candidates.size - CANDIDATE_WINDOW_SIZE).coerceAtLeast(0),
            )
            workspace.candidates.drop(windowStart).take(CANDIDATE_WINDOW_SIZE).forEachIndexed { offset, item ->
                val index = windowStart + offset
                appendLine(
                    "${if (index == selectedCandidateIndex) "▶" else "•"} ${item.candidateId} • " +
                        "priority ${item.researchPriority}/100 • ${item.evidenceStatus} • ${item.retainedRecords} records"
                )
            }
        }, IndicatorLevel.CHECK)
        addActions(
            Action("Previous candidate", selectedCandidateIndex > 0) {
                selectedCandidateIndex--
                render()
            },
            Action("Next candidate", selectedCandidateIndex < workspace.candidates.lastIndex) {
                selectedCandidateIndex++
                render()
            },
        )
        val selected = workspace.candidates[selectedCandidateIndex]
        inspector.text = candidateInspector(selected)
    }

    private fun renderRegistry() {
        val currentReadings = currentStandardObdReadings(runtime)
        addTitle("4Runner Signal Registry")
        addCard(buildString {
            appendLine("STANDARD OBD • CURRENTLY AVAILABLE ${currentReadings.size}")
            currentReadings.map { it.signalId }.distinct().sorted().forEach {
                appendLine("✓ $it • definition ${currentReadings.first { value -> value.signalId == it }.definitionRevision.take(12)}…")
            }
            if (currentReadings.isEmpty()) appendLine("No current supported-PID proof.")
            appendLine()
            appendLine("TOYOTA RAW CAN VALIDATED  0")
            appendLine("RESEARCH ITEMS  ${workspace.candidates.size}")
            appendLine("PROMOTION READY  0")
            append("Unknown remains unknown; candidates cannot update owner gauges or health models.")
        }, if (currentReadings.isEmpty()) IndicatorLevel.WAIT else IndicatorLevel.CHECK)
        inspector.text = "PROMOTION CHECKLIST\nSignal definition\nSource\nDecoder\nType + unit\nPlausible range\nFreshness\nApplicability\nTarget capture\nIndependent corroboration\nGolden replay\n\nNo current raw-CAN research item satisfies the complete portable validation checklist, so Promote is unavailable."
    }

    private fun renderReplay() {
        val hasEvidence = (workspace.summary?.canObservations ?: 0) > 0
        val replayAllowed = hasEvidence && !replayRunning && !runtime.running && workspace.scope != null
        addTitle("Replay Lab")
        addCard(when (val replay = replayState) {
            ReplayState.Idle -> "$HISTORICAL_REPLAY_LABEL\nReady to send encrypted-store observations through the production VHOS envelope, stream, CRC, and CAN decoders."
            is ReplayState.Running -> "$HISTORICAL_REPLAY_LABEL\nDECODING ${replay.progress.recordIndex}/${replay.progress.totalExpectedRecords}\nSession ${replay.progress.record.sessionId} • sequence ${replay.progress.record.sourceSequence} • ${identifierHex(replay.progress.record.identifier)}"
            is ReplayState.Complete -> replayReportText(replay.report)
            is ReplayState.Failed -> "$HISTORICAL_REPLAY_LABEL\nBLOCKED\n${replay.error}"
        }, when (replayState) {
            is ReplayState.Running -> IndicatorLevel.ACTIVE
            is ReplayState.Complete -> if ((replayState as ReplayState.Complete).report.passed) IndicatorLevel.PASS else IndicatorLevel.BLOCKED
            is ReplayState.Failed -> IndicatorLevel.BLOCKED
            ReplayState.Idle -> IndicatorLevel.WAIT
        })
        addActions(
            Action("Replay production decoder", replayAllowed) { startReplay(1) },
            Action("Load replay ×20 (10k window)", replayAllowed) { startReplay(20) },
            Action("Stop", replayRunning, ::stopReplay),
        )
        if (runtime.running) {
            addCard(
                "LIVE SESSION ACTIVE\nStop the vehicle session before historical replay so replay cannot contend with BLE ingestion or be mistaken for live evidence.",
                IndicatorLevel.BLOCKED,
            )
            addActions(Action("Stop live session", true, ::stopVehicleSession))
        }
        inspector.text = "REPLAY AUTHORITY\nRead-only replay is permitted when motion is unknown because it cannot control or query the vehicle. Replayed data is historical and never presented as live.\n\nPRODUCTION PATH\nVHOS envelope → CRC32C → self-resynchronizing stream decoder → CAN observation decoder."
    }

    private fun renderProgress() {
        val report = workspace.report
        val candidatePresent = workspace.candidates.count { it.retainedRecords > 0 }
        val standard = currentStandardObdReadings(runtime).map { it.signalId }.distinct().size
        addTitle("Discovery Progress")
        addCard(buildString {
            appendLine("ANALYSIS WINDOW • NEWEST ${formatCount(DISCOVERY_RECORD_LIMIT.toLong())} MAX")
            appendLine("Records  ${report?.acquisition?.records ?: 0}")
            appendLine("Sessions  ${report?.acquisition?.sessions ?: 0}")
            appendLine("Unique identifiers  ${report?.acquisition?.uniqueIdentifiers ?: 0}")
            appendLine("Dynamic identifiers  ${report?.identifierActivity?.count { it.dynamicBytePositions.isNotEmpty() } ?: 0}")
            appendLine()
            appendLine("DISCOVERY PIPELINE")
            appendLine("Standard values available now  $standard")
            appendLine("Research candidates present  $candidatePresent")
            appendLine("Vehicle-validated raw CAN  0")
            appendLine("Promoted raw CAN  0")
            append("Used by vehicle health models  0 from raw-CAN research")
        }, if (report == null) IndicatorLevel.WAIT else IndicatorLevel.CHECK)
        inspector.text = "COVERAGE AUTHORITY\nThis first progress view reports evidence counts, not percent-understood estimates. A percentage denominator requires a versioned vehicle capability inventory that does not exist yet."
    }

    private fun beginCapture(template: AndroidDiscoveryTestTemplate) {
        val parked = authoritativeParkedGate()
        if (!parked.allowed || template.executionAuthority != AndroidDiscoveryExecutionAuthority.PARKED_PASSIVE) {
            toast("Capture blocked: ${parked.detail}")
            return
        }
        if (workspace.scope == null) return toast("Capture blocked: vehicle/source scope is unresolved.")
        val store = database ?: return toast("Encrypted evidence store is not ready.")
        Thread {
            try {
                val currentGate = authoritativeParkedGate()
                require(currentGate.allowed) {
                    "Capture blocked because fresh PARKED authority was lost before persistence."
                }
                val authorization = requireNotNull(currentGate.authorization)
                val scope = requireNotNull(store.resolveDiscoveryEvidenceScope(authorization.sourceId)) {
                    "Vehicle/source scope is unresolved."
                }
                require(scope.sourceId == authorization.sourceId) {
                    "PARKED authority does not belong to the scoped gateway."
                }
                val counts = store.evidenceCounts(scope)
                val anchor = store.latestDiscoveryEvidenceAnchor(scope)
                val finalGate = authoritativeParkedGate()
                require(finalGate.allowed && finalGate.authorization?.sourceId == scope.sourceId) {
                    "Capture blocked because PARKED authority changed before the database write."
                }
                require(store.resolveDiscoveryEvidenceScope(scope.sourceId) == scope) {
                    "Capture blocked because the vehicle/profile scope changed before persistence."
                }
                store.beginDiscoveryCapture(
                    AndroidDiscoveryCaptureDraft(
                        sessionId = "android-discovery-draft-${UUID.randomUUID()}",
                        vehicleScopeId = scope.vehicleScopeId,
                        vehicleProfileRevisionId = scope.vehicleProfileRevisionId,
                        sourceId = scope.sourceId,
                        testTemplateId = template.templateId,
                        testTemplateVersion = template.version,
                        testTemplateSnapshot = template,
                        state = AndroidCaptureDraftState.ACTIVE,
                        startedAt = Instant.now().toString(),
                        startedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        startedBootId = bootId,
                        endedAt = null,
                        endedElapsedRealtimeNanos = null,
                        endedBootId = null,
                        startAnchor = anchor,
                        endAnchor = null,
                        startLogicalFrameCount = counts.logicalFrames,
                        startCanObservationCount = counts.canObservations,
                        endLogicalFrameCount = null,
                        endCanObservationCount = null,
                        safetyEvidence = AndroidDiscoverySafetyEvidence.VALIDATED_GATEWAY_HEALTH_PARKED,
                        safetyAuthorization = requireNotNull(finalGate.authorization),
                        finalizationAuthority = null,
                        finalizationSafetyAuthorization = null,
                    ).validate()
                )
                refreshWorkspace()
                selectOnUi(Section.CAPTURES)
            } catch (error: Exception) {
                toast(error.message ?: error.javaClass.simpleName)
            }
        }.start()
    }

    private fun addMarker(definition: AndroidDiscoveryMarkerDefinition, value: String? = null, note: String? = null) {
        val parked = authoritativeParkedGate()
        if (!parked.allowed) return toast("Marker blocked: ${parked.detail}")
        val store = database ?: return toast("Encrypted evidence store is not ready.")
        val active = activeCapture()?.session ?: return toast("No capture is active.")
        if (definition.kind == AndroidDiscoveryMarkerKind.MANUAL_MEASUREMENT && value == null) {
            showMeasurementDialog(definition)
            return
        }
        Thread {
            try {
                val currentGate = authoritativeParkedGate()
                require(currentGate.allowed) {
                    "Marker blocked because fresh PARKED authority was lost before persistence."
                }
                val authorization = requireNotNull(currentGate.authorization)
                require(authorization.sourceId == active.sourceId) {
                    "Marker blocked because PARKED authority belongs to a different gateway."
                }
                val currentScope = requireNotNull(store.resolveDiscoveryEvidenceScope(active.sourceId)) {
                    "Marker blocked because the current vehicle/source scope is unresolved."
                }
                require(currentScope == active.evidenceScope()) {
                    "Marker blocked because the active capture belongs to another vehicle/profile revision."
                }
                val anchor = store.latestDiscoveryEvidenceAnchor(currentScope)
                val finalGate = authoritativeParkedGate()
                require(finalGate.allowed && finalGate.authorization?.sourceId == active.sourceId) {
                    "Marker blocked because PARKED authority changed before the database write."
                }
                store.appendDiscoveryMarker(
                    AndroidDiscoveryMarkerRecord(
                        markerId = "android-marker-${UUID.randomUUID()}",
                        captureSessionId = active.sessionId,
                        eventType = definition.eventType,
                        label = definition.label,
                        kind = definition.kind,
                        value = value,
                        unit = definition.suggestedUnit,
                        observedAt = Instant.now().toString(),
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        evidenceAnchor = anchor,
                        observer = "owner",
                        note = note,
                        safetyAuthorization = requireNotNull(finalGate.authorization),
                    ).validate()
                )
                refreshWorkspace()
            } catch (error: Exception) {
                toast(error.message ?: error.javaClass.simpleName)
            }
        }.start()
    }

    private fun showMeasurementDialog(definition: AndroidDiscoveryMarkerDefinition) {
        val input = EditText(this).apply {
            hint = definition.suggestedUnit ?: "value"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(definition.label)
            .setMessage("Enter the independent observed value. It will remain a manual measurement with its original unit and timestamp.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Mark", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                val number = text.toDoubleOrNull()
                if (number == null || !number.isFinite()) {
                    input.error = "Enter a finite number."
                } else {
                    dialog.dismiss()
                    addMarker(definition, text)
                }
            }
        }
        dialog.show()
    }

    private fun showCustomMarkerDialog() {
        val input = EditText(this).apply {
            hint = "What happened?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Custom observed event")
            .setMessage("Describe only what was directly observed. Do not assign an unvalidated signal meaning.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Mark", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = input.text.toString().trim()
                if (label.isEmpty() || label.length > 160) input.error = "Enter 1–160 characters."
                else {
                    dialog.dismiss()
                    addMarker(
                        AndroidDiscoveryMarkerDefinition(
                            eventType = "event.custom",
                            label = label,
                            kind = AndroidDiscoveryMarkerKind.OBSERVATION,
                        ).validate()
                    )
                }
            }
        }
        dialog.show()
    }

    private fun finalizeCapture(state: AndroidCaptureDraftState) {
        require(state != AndroidCaptureDraftState.ACTIVE)
        val store = database ?: return toast("Encrypted evidence store is not ready.")
        val active = activeCapture()?.session ?: return toast("No capture is active.")
        if (state == AndroidCaptureDraftState.COMPLETED && !authoritativeParkedGate().allowed) {
            return toast("Completion blocked because PARKED authority is no longer verified. Abort instead.")
        }
        Thread {
            try {
                val currentGate = authoritativeParkedGate()
                val interruptedByReboot = active.startedBootId != bootId
                if (state == AndroidCaptureDraftState.COMPLETED) {
                    require(!interruptedByReboot && currentGate.allowed) {
                        "Completion blocked because fresh PARKED authority was lost before persistence."
                    }
                    require(currentGate.authorization?.sourceId == active.sourceId) {
                        "Completion blocked because PARKED authority belongs to a different gateway."
                    }
                }
                val activeScope = active.evidenceScope()
                if (state == AndroidCaptureDraftState.COMPLETED) {
                    val currentScope = requireNotNull(store.resolveDiscoveryEvidenceScope(active.sourceId)) {
                        "Completion blocked because the current vehicle/source scope is unresolved."
                    }
                    require(currentScope == activeScope) {
                        "Completion blocked because the active capture belongs to another vehicle/profile revision."
                    }
                }
                val counts = store.evidenceCounts(activeScope)
                val endAnchor = store.latestDiscoveryEvidenceAnchor(activeScope)
                val finalGate = if (state == AndroidCaptureDraftState.COMPLETED) {
                    authoritativeParkedGate().also { gate ->
                        require(gate.allowed && gate.authorization?.sourceId == active.sourceId) {
                            "Completion blocked because PARKED authority changed before the database write."
                        }
                    }
                } else null
                store.finalizeDiscoveryCapture(
                    active.copy(
                        state = state,
                        endedAt = Instant.now().toString(),
                        endedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        endedBootId = bootId,
                        endAnchor = endAnchor,
                        endLogicalFrameCount = counts.logicalFrames,
                        endCanObservationCount = counts.canObservations,
                        finalizationAuthority = when {
                            interruptedByReboot -> AndroidCaptureFinalizationAuthority.INTERRUPTED_BY_REBOOT
                            state == AndroidCaptureDraftState.COMPLETED ->
                                AndroidCaptureFinalizationAuthority.PARKED_VERIFIED_COMPLETION
                            else -> AndroidCaptureFinalizationAuthority.OWNER_SAFETY_ABORT
                        },
                        finalizationSafetyAuthorization = if (
                            state == AndroidCaptureDraftState.COMPLETED
                        ) finalGate?.authorization else null,
                    ).validate()
                )
                refreshWorkspace()
            } catch (error: Exception) {
                toast(error.message ?: error.javaClass.simpleName)
            }
        }.start()
    }

    private fun saveCapabilityObservation() {
        val store = database ?: return toast("Encrypted evidence store is not ready.")
        if (!authoritativeParkedGate().allowed) {
            return toast("Capability observation blocked: fresh PARKED authority is required.")
        }
        Thread {
            try {
                val initialSnapshot = HeadUnitRuntime.snapshot()
                val gate = parkedGate(initialSnapshot)
                require(gate.allowed) {
                    "Capability observation blocked because PARKED authority expired before persistence."
                }
                val authorization = requireNotNull(gate.authorization)
                val scope = requireNotNull(store.resolveDiscoveryEvidenceScope(authorization.sourceId)) {
                    "Vehicle/source scope is unresolved."
                }
                require(scope.sourceId == authorization.sourceId) {
                    "Capability source does not match PARKED authority."
                }
                val summary = store.discoveryEvidenceSummary(scope)
                val snapshot = HeadUnitRuntime.snapshot()
                val finalGate = parkedGate(snapshot)
                require(finalGate.allowed && finalGate.authorization?.sourceId == scope.sourceId) {
                    "Capability observation blocked because PARKED authority changed before the database write."
                }
                require(store.resolveDiscoveryEvidenceScope(scope.sourceId) == scope) {
                    "Capability observation blocked because the vehicle/profile scope changed before persistence."
                }
                val obd = snapshot.obd
                val healthIsCurrent = hasFreshStreamingContract(snapshot) &&
                    obd.vehicleMotionObservedAtEpochMs?.let {
                        System.currentTimeMillis() - it in 0..LIVE_FRESHNESS_MS
                    } == true
                val observation = AndroidVehicleCapabilityObservation(
                    snapshotId = "android-capability-${UUID.randomUUID()}",
                    capturedAt = Instant.now().toString(),
                    vehicleScopeId = scope.vehicleScopeId,
                    vehicleProfileRevisionId = scope.vehicleProfileRevisionId,
                    sourceId = scope.sourceId,
                    gatewayFirmwareVersion = obd.firmwareVersion,
                    gatewayContractActive = hasFreshStreamingContract(snapshot),
                    listenOnlyProven = if (healthIsCurrent) obd.listenOnly else null,
                    canCommunicationDetected = if (healthIsCurrent) {
                        AndroidDiscoveryLiveEvidence.isVehicleBusCurrent(
                            obd,
                            scope.sourceId,
                            freshnessMillis = LIVE_FRESHNESS_MS,
                        )
                    } else null,
                    canBitratesBps = buildSet {
                        if (healthIsCurrent) obd.bitrateBps?.takeIf { it > 0 }?.let(::add)
                    }.sorted(),
                    retainedCanObservations = summary.canObservations,
                    uniqueCanIdentifiers = summary.uniqueCanIdentifiers
                        .takeIf { summary.canObservations > 0 },
                    obdEcuCount = if (healthIsCurrent) obd.j1979EcuCount else 0,
                    obdEnumerationComplete = if (healthIsCurrent) obd.j1979EnumerationComplete else null,
                    supportedObdPidCount = if (healthIsCurrent) obd.j1979SupportedPidCount else 0,
                    availableStandardSignalIds = if (healthIsCurrent) {
                        obd.standardObdReadings.map { it.signalId }.distinct().sorted()
                    } else {
                        emptyList()
                    },
                    safetyAuthorization = requireNotNull(finalGate.authorization),
                ).validate()
                val inserted = store.persistAndroidVehicleCapabilityObservation(observation)
                toast(if (inserted) "Android capability observation appended." else "This capability state is already recorded.")
                refreshWorkspace()
            } catch (error: Exception) {
                toast(error.message ?: error.javaClass.simpleName)
            }
        }.start()
    }

    private fun startReplay(repeat: Int) {
        val store = database ?: return toast("Encrypted evidence store is not ready.")
        val scope = workspace.scope ?: return toast("Replay blocked: vehicle/source scope is unresolved.")
        if (runtime.running) return toast("Replay blocked while a live vehicle session is active.")
        val generation = ++replayGeneration
        replayRunning = true
        Thread {
            try {
                require(!HeadUnitRuntime.snapshot().running) {
                    "Replay blocked because a live vehicle session started."
                }
                val inputLimit = if (repeat > 1) REPLAY_LOAD_RECORD_LIMIT else DISCOVERY_RECORD_LIMIT
                val input = store.recentCanObservations(scope, inputLimit)
                    .map { DiscoveryObservation(it.sourceId, it.observation) }
                val report = HistoricalCanReplay.run(
                    input = input,
                    repeat = repeat,
                    shouldContinue = {
                        !destroyed && replayRunning && replayGeneration == generation &&
                            !HeadUnitRuntime.snapshot().running
                    },
                    onRecord = { progress ->
                        if (progress.recordIndex == 1 || progress.recordIndex % REPLAY_UI_PROGRESS_INTERVAL == 0 ||
                            progress.recordIndex == progress.totalExpectedRecords
                        ) updateReplay(generation, ReplayState.Running(progress))
                    },
                )
                replayRunning = false
                updateReplay(generation, ReplayState.Complete(report))
            } catch (error: Exception) {
                replayRunning = false
                updateReplay(generation, ReplayState.Failed(error.message ?: error.javaClass.simpleName))
            }
        }.start()
    }

    private fun stopReplay() {
        replayRunning = false
        replayGeneration++
        replayState = ReplayState.Idle
        render()
    }

    private fun updateReplay(generation: Int, next: ReplayState) {
        if (destroyed || generation != replayGeneration) return
        runOnUiThread {
            if (!destroyed && generation == replayGeneration) {
                replayState = next
                if (selectedSection == Section.REPLAY) render()
            }
        }
    }

    private fun parkedGate(snapshot: HeadUnitSnapshot): AndroidDiscoveryEngineeringGate =
        AndroidDiscoveryEngineeringSafetyGate.evaluate(snapshot.obd)

    /** Mutations must bypass the conflated UI observer and inspect the latest synchronous state. */
    private fun authoritativeParkedGate(): AndroidDiscoveryEngineeringGate =
        parkedGate(HeadUnitRuntime.snapshot())

    private fun activeCapture(): PersistedAndroidDiscoveryCapture? =
        workspace.activeCapture

    private fun AndroidDiscoveryCaptureDraft.evidenceScope(): DiscoveryEvidenceScope =
        DiscoveryEvidenceScope(
            vehicleScopeId = vehicleScopeId,
            vehicleProfileRevisionId = vehicleProfileRevisionId,
            sourceId = sourceId,
        ).validate()

    private fun selectedTemplate(): AndroidDiscoveryTestTemplate =
        AndroidDiscoveryTestLibrary.templates[selectedTemplateIndex.coerceIn(0, AndroidDiscoveryTestLibrary.templates.lastIndex)]

    private fun select(section: Section) {
        selectedSection = section
        render()
    }

    private fun selectOnUi(section: Section) = runOnUiThread {
        if (!destroyed) select(section)
    }

    private fun startVehicleSession() {
        startForegroundService(
            Intent(this, VehicleSessionService::class.java).setAction(VehicleSessionService.ACTION_START)
        )
    }

    private fun stopVehicleSession() {
        startService(
            Intent(this, VehicleSessionService::class.java).setAction(VehicleSessionService.ACTION_STOP)
        )
    }

    private fun candidateInspector(item: AndroidCandidateResearchItem): String = buildString {
        appendLine("CANDIDATE REVIEW")
        appendLine(item.candidateId)
        appendLine(item.sourceDescription)
        appendLine("Proposed semantic: ${item.proposedCanonicalSignalId ?: "UNMAPPED"}")
        appendLine("Research priority: ${item.researchPriority}/100")
        appendLine("Confidence: ${item.confidence?.let { decimal(it * 100.0) + "%" } ?: "NOT CALCULATED"}")
        appendLine("Captures: ${item.captureSessions} • records ${item.retainedRecords}")
        appendLine()
        appendLine("PROMOTION GATE")
        val gate = item.promotionChecklist
        appendLine("${check(gate.signalDefinition)} Signal definition")
        appendLine("${check(gate.sourceDefined)} Source defined")
        appendLine("${check(gate.decoderDefined)} Decoder defined")
        appendLine("${check(gate.unitsAndTypeDefined)} Type + unit")
        appendLine("${check(gate.plausibleRangeDefined)} Plausible range")
        appendLine("${check(gate.freshnessRuleDefined)} Freshness")
        appendLine("${check(gate.targetVehicleCapture)} Target capture")
        appendLine("${check(gate.independentCorroboration)} Independent corroboration")
        appendLine("${check(gate.goldenReplay)} Golden replay")
        appendLine()
        appendLine("NEXT TEST")
        appendLine(item.nextValidation)
        appendLine()
        append(item.authority)
    }

    private fun replayReportText(report: HistoricalReplayReport): String = buildString {
        appendLine(report.label)
        appendLine(if (report.passed) "TRANSPORT PASS" else if (report.cancelled) "CANCELLED" else "TRANSPORT FAIL")
        appendLine("${report.decodedRecords}/${report.expectedRecordsAfterFaults} exact decoded records")
        appendLine("${report.sessions} sessions • ${report.uniqueIdentifiers} identifiers • repeat ${report.repeat}×")
        appendLine("Recoveries ${report.decoderRecoveries} • corrupt ${report.decoderCorruptCandidates} • discarded ${report.decoderDiscardedBytes} bytes")
        append("Order + payload identity ${if (report.exactRecordOrderAndPayloadMatch) "VERIFIED" else "FAILED"}")
    }

    private fun obdStatus(snapshot: HeadUnitSnapshot): String = if (
        !hasFreshStreamingContract(snapshot) || snapshot.obd.sourceId != workspace.scope?.sourceId
    ) {
        "UNKNOWN / NO CURRENT SUPPORTED-PID EVIDENCE"
    } else when (snapshot.obd.j1979EnumerationComplete) {
        true -> "${snapshot.obd.j1979EcuCount} ECU • ${snapshot.obd.j1979SupportedPidCount} supported PIDs • COMPLETE"
        false -> "${snapshot.obd.j1979EcuCount} ECU • enumeration INCOMPLETE"
        null -> "UNKNOWN / NO SUPPORTED-PID EVIDENCE"
    }

    private fun hasFreshStreamingContract(snapshot: HeadUnitSnapshot): Boolean =
        snapshot.obd.phase == ConnectionPhase.STREAMING &&
            snapshot.obd.lastFrameAtEpochMs?.let {
                System.currentTimeMillis() - it in 0..LIVE_FRESHNESS_MS
            } == true

    private fun hasFreshGatewayHealth(snapshot: HeadUnitSnapshot): Boolean =
        snapshot.obd.vehicleMotionObservedAtEpochMs?.let {
            System.currentTimeMillis() - it in 0..LIVE_FRESHNESS_MS
        } == true

    private fun currentStandardObdReadings(snapshot: HeadUnitSnapshot) =
        if (hasFreshStreamingContract(snapshot) && snapshot.obd.sourceId == workspace.scope?.sourceId) {
            snapshot.obd.standardObdReadings.filter { reading ->
                runCatching { Instant.parse(reading.observedAt).toEpochMilli() }
                    .getOrNull()
                    ?.let { System.currentTimeMillis() - it in 0..LIVE_FRESHNESS_MS } == true
            }
        } else {
            emptyList()
        }

    private fun addTitle(value: String) {
        content.addView(TextView(this).apply {
            text = value
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.vhos_text))
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun addCard(value: String, level: IndicatorLevel) {
        content.addView(TextView(this).apply {
            text = value.trimEnd()
            textSize = 16f
            setLineSpacing(0f, 1.12f)
            setTextColor(levelColor(level))
            setBackgroundColor(getColor(R.color.vhos_surface))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) })
    }

    private fun addActions(vararg actions: Action) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.forEach { action -> row.addView(actionButton(action.label, action.enabled, action.action)) }
        content.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })
    }

    private fun actionButton(label: String, enabled: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        textSize = 13f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
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

    private fun check(value: Boolean) = if (value) "✓" else "✕"
    private fun decimal(value: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", value)
    private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.0f MiB", bytes / (1024.0 * 1024.0))
    }
    private fun identifierHex(identifier: UInt): String = String.format(Locale.US, "0x%03X", identifier.toInt())
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = runOnUiThread {
        if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private enum class Section(val label: String) {
        OVERVIEW("Overview"),
        SIGNALS("Live Signals"),
        TESTS("Test Library"),
        CAPTURES("Capture Sessions"),
        CANDIDATES("Candidate Inbox"),
        REGISTRY("Signal Registry"),
        REPLAY("Replay Lab"),
        PROGRESS("Discovery Progress"),
    }

    private data class Action(val label: String, val enabled: Boolean, val action: () -> Unit)
    private data class CandidateResult(val items: List<AndroidCandidateResearchItem>, val error: String?)
    private data class WorkspaceData(
        val loading: Boolean,
        val scope: DiscoveryEvidenceScope?,
        val summary: DiscoveryEvidenceSummary?,
        val report: CanDiscoveryReport?,
        val candidates: List<AndroidCandidateResearchItem>,
        val candidateError: String?,
        val captures: List<PersistedAndroidDiscoveryCapture>,
        val activeCapture: PersistedAndroidDiscoveryCapture?,
        val activeMarkers: List<AndroidDiscoveryMarkerRecord>,
        val capabilityObservations: List<AndroidVehicleCapabilityObservation>,
        val loadError: String?,
    ) {
        companion object {
            fun loading() = WorkspaceData(
                true, null, null, null, emptyList(), null, emptyList(), null, emptyList(), emptyList(), null,
            )
            fun failed(error: String) = WorkspaceData(
                false, null, null, null, emptyList(), null, emptyList(), null, emptyList(), emptyList(), error,
            )
        }
    }

    private sealed interface ReplayState {
        data object Idle : ReplayState
        data class Running(val progress: HistoricalReplayProgress) : ReplayState
        data class Complete(val report: HistoricalReplayReport) : ReplayState
        data class Failed(val error: String) : ReplayState
    }

    companion object {
        private const val DISCOVERY_RECORD_LIMIT = 100_000
        private const val REPLAY_LOAD_RECORD_LIMIT = 10_000
        private const val CANDIDATE_WINDOW_SIZE = 20
        private const val LIVE_FRESHNESS_MS = 5_000L
        private const val RUNTIME_RENDER_INTERVAL_MILLIS = 250L
        private const val REPLAY_UI_PROGRESS_INTERVAL = 2_048
        private val EMPTY_DISCOVERY_SUMMARY = DiscoveryEvidenceSummary(0, 0, 0, null, null)
    }
}
