package dev.vhos.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vhos.discovery.AndroidCaptureDraftState
import dev.vhos.discovery.AndroidCaptureFinalizationAuthority
import dev.vhos.discovery.AndroidDiscoveryCaptureDraft
import dev.vhos.discovery.AndroidDiscoveryEvidenceAnchor
import dev.vhos.discovery.AndroidDiscoveryMarkerKind
import dev.vhos.discovery.AndroidDiscoveryMarkerRecord
import dev.vhos.discovery.AndroidDiscoveryMutationAuthority
import dev.vhos.discovery.AndroidDiscoverySafetyEvidence
import dev.vhos.discovery.AndroidDiscoverySafetyAuthorization
import dev.vhos.discovery.AndroidDiscoveryTestLibrary
import dev.vhos.discovery.AndroidVehicleCapabilityObservation
import dev.vhos.discovery.DiscoveryEvidenceProvenance
import dev.vhos.digitaltwin.VehicleProfile
import dev.vhos.model.DeviceRole
import dev.vhos.model.VehicleMotion
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import dev.vhos.protocol.CanObservation
import dev.vhos.protocol.Crc32c
import dev.vhos.sync.BundleCreator
import dev.vhos.sync.EvidenceBundleManifest
import dev.vhos.sync.EvidenceBundles
import dev.vhos.sync.PortableEvidenceRecord
import dev.vhos.sync.RecoveryEvidenceMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DiscoveryEvidencePersistenceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetStore() {
        EvidenceDatabase.closeForInstrumentationTests()
        context.deleteDatabase(EvidenceDatabase.DATABASE_NAME)
    }

    @After
    fun cleanUp() {
        EvidenceDatabase.closeForInstrumentationTests()
        context.deleteDatabase(EvidenceDatabase.DATABASE_NAME)
    }

    @Test
    fun persistsOneActiveCaptureMarkersFinalizationAndCapabilityDeduplication() {
        val store = EvidenceDatabase.open(context)
        assertEquals(12, store.readableDatabase.version)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val template = AndroidDiscoveryTestLibrary.requireTemplate(
            "vhos.discovery.brake-pulse",
            AndroidDiscoveryTestLibrary.CONTRACT_VERSION,
        )
        val active = AndroidDiscoveryCaptureDraft(
            sessionId = "android-discovery-draft-instrumentation",
            vehicleScopeId = scope.vehicleScopeId,
            vehicleProfileRevisionId = scope.vehicleProfileRevisionId,
            sourceId = scope.sourceId,
            testTemplateId = template.templateId,
            testTemplateVersion = template.version,
            testTemplateSnapshot = template,
            state = AndroidCaptureDraftState.ACTIVE,
            startedAt = "2026-08-22T00:00:00Z",
            startedElapsedRealtimeNanos = 100,
            startedBootId = "boot-instrumentation",
            endedAt = null,
            endedElapsedRealtimeNanos = null,
            endedBootId = null,
            startAnchor = null,
            endAnchor = null,
            startLogicalFrameCount = 0,
            startCanObservationCount = 0,
            endLogicalFrameCount = null,
            endCanObservationCount = null,
            safetyEvidence = AndroidDiscoverySafetyEvidence.VALIDATED_GATEWAY_HEALTH_PARKED,
            safetyAuthorization = authorization,
            finalizationAuthority = null,
            finalizationSafetyAuthorization = null,
        ).validate()

        assertThrows(IllegalArgumentException::class.java) {
            store.beginDiscoveryCaptureAt(
                active.copy(
                    safetyAuthorization = authorization.copy(
                        receivedAtEpochMillis = TEST_NOW_EPOCH_MILLIS - 5_001,
                    ),
                ),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.beginDiscoveryCaptureAt(
                active.copy(
                    safetyAuthorization = authorization.copy(
                        receivedAtEpochMillis = TEST_NOW_EPOCH_MILLIS + 1,
                    ),
                ),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        store.beginDiscoveryCaptureAt(active, TEST_NOW_EPOCH_MILLIS)
        assertThrows(IllegalArgumentException::class.java) {
            store.beginDiscoveryCaptureAt(
                active.copy(sessionId = "second-active-draft"),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        fun marker(
            markerId: String,
            markerAuthorization: AndroidDiscoverySafetyAuthorization = authorization,
        ) = AndroidDiscoveryMarkerRecord(
                markerId = markerId,
                captureSessionId = active.sessionId,
                eventType = "event.brake.pressed",
                label = "Brake pressed",
                kind = AndroidDiscoveryMarkerKind.STATE,
                value = null,
                unit = null,
                observedAt = "2026-08-22T00:00:01Z",
                elapsedRealtimeNanos = 110,
                observedBootId = "boot-instrumentation",
                evidenceAnchor = null,
                observer = "owner",
                note = null,
                safetyAuthorization = markerAuthorization,
            ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                marker(
                    markerId = "stale-android-marker-instrumentation",
                    markerAuthorization = authorization.copy(
                        receivedAtEpochMillis = TEST_NOW_EPOCH_MILLIS - 5_001,
                    ),
                ),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        store.appendDiscoveryMarkerAt(
            marker("android-marker-instrumentation"),
            TEST_NOW_EPOCH_MILLIS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                marker("marker-with-regressed-clock").copy(
                    observedAt = "2026-08-22T00:00:00.500Z",
                    elapsedRealtimeNanos = 109,
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                marker("marker-from-another-boot").copy(
                    observedAt = "2026-08-22T00:00:02Z",
                    elapsedRealtimeNanos = 111,
                    observedBootId = "other-boot",
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        assertEquals(active, store.activeDiscoveryCapture()?.session)
        assertEquals(1, store.eventMarkers(active.sessionId).size)

        val completed = active.copy(
                state = AndroidCaptureDraftState.COMPLETED,
                endedAt = "2026-08-22T00:00:02Z",
                endedElapsedRealtimeNanos = 120,
                endedBootId = "boot-instrumentation",
                endLogicalFrameCount = 0,
                endCanObservationCount = 0,
                finalizationAuthority = AndroidCaptureFinalizationAuthority.PARKED_VERIFIED_COMPLETION,
                finalizationSafetyAuthorization = authorization.copy(healthFrameSequence = 8UL),
            ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeDiscoveryCaptureAt(
                completed.copy(
                    endedAt = "2026-08-22T00:00:00.500Z",
                    endedElapsedRealtimeNanos = 109,
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeDiscoveryCaptureAt(
                completed.copy(
                    finalizationSafetyAuthorization = requireNotNull(
                        completed.finalizationSafetyAuthorization,
                    ).copy(receivedAtEpochMillis = TEST_NOW_EPOCH_MILLIS - 5_001),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        store.finalizeDiscoveryCaptureAt(
            completed,
            TEST_NOW_EPOCH_MILLIS,
        )

        assertNull(store.activeDiscoveryCapture())
        assertEquals(AndroidCaptureDraftState.COMPLETED, store.recentDiscoveryCaptures().single().session.state)
        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                AndroidDiscoveryMarkerRecord(
                    markerId = "marker-after-finalization",
                    captureSessionId = active.sessionId,
                    eventType = "event.brake.released",
                    label = "Brake released",
                    kind = AndroidDiscoveryMarkerKind.STATE,
                    value = null,
                    unit = null,
                    observedAt = "2026-08-22T00:00:03Z",
                    elapsedRealtimeNanos = 130,
                    observedBootId = "boot-instrumentation",
                    evidenceAnchor = null,
                    observer = "owner",
                    note = null,
                    safetyAuthorization = authorization,
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        val capability = AndroidVehicleCapabilityObservation(
            snapshotId = "android-capability-instrumentation-1",
            capturedAt = "2026-08-22T00:00:03Z",
            vehicleScopeId = active.vehicleScopeId,
            vehicleProfileRevisionId = active.vehicleProfileRevisionId,
            sourceId = active.sourceId,
            gatewayFirmwareVersion = null,
            gatewayContractActive = false,
            listenOnlyProven = null,
            canCommunicationDetected = null,
            canBitratesBps = emptyList(),
            retainedCanObservations = 0,
            uniqueCanIdentifiers = null,
            obdEcuCount = 0,
            obdEnumerationComplete = null,
            supportedObdPidCount = 0,
            availableStandardSignalIds = emptyList(),
            safetyAuthorization = authorization,
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.persistAndroidVehicleCapabilityObservationAt(
                capability.copy(
                    snapshotId = "stale-android-capability-instrumentation",
                    safetyAuthorization = authorization.copy(
                        receivedAtEpochMillis = TEST_NOW_EPOCH_MILLIS - 5_001,
                    ),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        assertTrue(store.persistAndroidVehicleCapabilityObservationAt(
            capability,
            TEST_NOW_EPOCH_MILLIS,
        ))
        assertFalse(store.persistAndroidVehicleCapabilityObservationAt(
            capability.copy(
                snapshotId = "android-capability-instrumentation-2",
                capturedAt = "2026-08-22T00:00:04Z",
            ),
            TEST_NOW_EPOCH_MILLIS,
        ))
        assertEquals(1, store.recentAndroidVehicleCapabilityObservations().size)
    }

    @Test
    fun rawEvidenceIsImmutablePerVehicleProfileAndActiveCaptureCannotCrossScopes() {
        val store = EvidenceDatabase.open(context)
        val firstScope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = CanObservation(
            sessionId = 11U,
            sourceSequence = 41UL,
            monotonicMicroseconds = 4_100UL,
            bitrateBps = 500_000,
            identifier = 0x2C4U,
            extended = false,
            remoteRequest = false,
            listenOnly = true,
            dataLength = 8,
            data = byteArrayOf(0x15, 0x6C, 0, 0, 0, 0, 0, 0),
        )
        assertTrue(persistRawObservation(store, firstScope, observation))

        val template = AndroidDiscoveryTestLibrary.requireTemplate(
            "vhos.discovery.brake-pulse",
            AndroidDiscoveryTestLibrary.CONTRACT_VERSION,
        )
        val active = AndroidDiscoveryCaptureDraft(
            sessionId = "scope-transition-capture",
            vehicleScopeId = firstScope.vehicleScopeId,
            vehicleProfileRevisionId = firstScope.vehicleProfileRevisionId,
            sourceId = firstScope.sourceId,
            testTemplateId = template.templateId,
            testTemplateVersion = template.version,
            testTemplateSnapshot = template,
            state = AndroidCaptureDraftState.ACTIVE,
            startedAt = "2026-08-22T00:00:00Z",
            startedElapsedRealtimeNanos = 100,
            startedBootId = "boot-instrumentation",
            endedAt = null,
            endedElapsedRealtimeNanos = null,
            endedBootId = null,
            startAnchor = null,
            endAnchor = null,
            startLogicalFrameCount = 0,
            startCanObservationCount = 1,
            endLogicalFrameCount = null,
            endCanObservationCount = null,
            safetyEvidence = AndroidDiscoverySafetyEvidence.VALIDATED_GATEWAY_HEALTH_PARKED,
            safetyAuthorization = authorization,
            finalizationAuthority = null,
            finalizationSafetyAuthorization = null,
        ).validate()
        store.beginDiscoveryCaptureAt(active, TEST_NOW_EPOCH_MILLIS)

        store.appendVehicleProfile(
            VehicleProfile(
                revisionId = PROFILE_TWO,
                supersedesRevisionId = PROFILE_ONE,
                createdAt = "2026-08-22T00:01:00Z",
            ).validate()
        )
        val secondScope = requireNotNull(store.resolveDiscoveryEvidenceScope(SOURCE_ID))
        assertTrue(persistRawObservation(store, secondScope, observation))

        assertEquals(1, store.recentCanObservations(firstScope).size)
        assertEquals(1, store.recentCanObservations(secondScope).size)
        assertEquals(PROFILE_ONE, store.recentCanObservations(firstScope).single().vehicleProfileRevisionId)
        assertEquals(PROFILE_TWO, store.recentCanObservations(secondScope).single().vehicleProfileRevisionId)
        assertEquals(
            DiscoveryEvidenceProvenance.LOCAL_AUTHORIZED,
            store.recentCanObservations(secondScope).single().provenance.classification,
        )
        assertEquals(1, store.discoveryEvidenceSummary(secondScope).localAuthorizedCanObservations)

        assertThrows(IllegalArgumentException::class.java) {
            store.persistAndroidVehicleCapabilityObservationAt(
                AndroidVehicleCapabilityObservation(
                    snapshotId = "stale-profile-capability",
                    capturedAt = "2026-08-22T00:01:00Z",
                    vehicleScopeId = firstScope.vehicleScopeId,
                    vehicleProfileRevisionId = firstScope.vehicleProfileRevisionId,
                    sourceId = firstScope.sourceId,
                    gatewayFirmwareVersion = null,
                    gatewayContractActive = false,
                    listenOnlyProven = null,
                    canCommunicationDetected = null,
                    canBitratesBps = emptyList(),
                    retainedCanObservations = 1,
                    uniqueCanIdentifiers = 1,
                    obdEcuCount = 0,
                    obdEnumerationComplete = null,
                    supportedObdPidCount = 0,
                    availableStandardSignalIds = emptyList(),
                    safetyAuthorization = authorization,
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                AndroidDiscoveryMarkerRecord(
                    markerId = "wrong-current-scope-marker",
                    captureSessionId = active.sessionId,
                    eventType = "event.brake.pressed",
                    label = "Brake pressed",
                    kind = AndroidDiscoveryMarkerKind.STATE,
                    value = null,
                    unit = null,
                    observedAt = "2026-08-22T00:01:01Z",
                    elapsedRealtimeNanos = 120,
                    observedBootId = "boot-instrumentation",
                    evidenceAnchor = null,
                    observer = "owner",
                    note = null,
                    safetyAuthorization = authorization.copy(healthFrameSequence = 8UL),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeDiscoveryCaptureAt(
                active.copy(
                    state = AndroidCaptureDraftState.COMPLETED,
                    endedAt = "2026-08-22T00:01:02Z",
                    endedElapsedRealtimeNanos = 130,
                    endedBootId = "boot-instrumentation",
                    endLogicalFrameCount = 0,
                    endCanObservationCount = 1,
                    finalizationAuthority = AndroidCaptureFinalizationAuthority.PARKED_VERIFIED_COMPLETION,
                    finalizationSafetyAuthorization = authorization.copy(healthFrameSequence = 9UL),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        // A fail-safe abort remains possible after a profile transition so no stale capture is stuck.
        store.finalizeDiscoveryCaptureAt(
            active.copy(
                state = AndroidCaptureDraftState.ABORTED,
                endedAt = "2026-08-22T00:01:03Z",
                endedElapsedRealtimeNanos = 140,
                endedBootId = "boot-instrumentation",
                endLogicalFrameCount = 0,
                endCanObservationCount = 1,
                finalizationAuthority = AndroidCaptureFinalizationAuthority.OWNER_SAFETY_ABORT,
                finalizationSafetyAuthorization = null,
            ).validate(),
            TEST_NOW_EPOCH_MILLIS,
        )
    }

    @Test
    fun selectorBootstrapPersistsExactLiveCanLineageAndMarkersAreAppendOnly() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val firstObservation = canObservation(sessionId = 73u, sequence = 100UL)
        assertTrue(persistRawObservation(store, scope, firstObservation))
        val firstAnchor = anchor(firstObservation)
        val template = AndroidDiscoveryTestLibrary.requireTemplate(
            AndroidDiscoveryTestLibrary.PARK_SELECTOR_BOOTSTRAP_TEMPLATE_ID,
            AndroidDiscoveryTestLibrary.CONTRACT_VERSION,
        )
        val bootstrap = bootstrapAuthorization(firstAnchor)
        val active = AndroidDiscoveryCaptureDraft(
            sessionId = "selector-bootstrap-instrumentation",
            vehicleScopeId = scope.vehicleScopeId,
            vehicleProfileRevisionId = scope.vehicleProfileRevisionId,
            sourceId = scope.sourceId,
            testTemplateId = template.templateId,
            testTemplateVersion = template.version,
            testTemplateSnapshot = template,
            state = AndroidCaptureDraftState.ACTIVE,
            startedAt = "2026-08-22T00:00:00Z",
            startedElapsedRealtimeNanos = 100,
            startedBootId = "boot-instrumentation",
            endedAt = null,
            endedElapsedRealtimeNanos = null,
            endedBootId = null,
            startAnchor = firstAnchor,
            endAnchor = null,
            startLogicalFrameCount = 1,
            startCanObservationCount = 1,
            endLogicalFrameCount = null,
            endCanObservationCount = null,
            safetyEvidence = AndroidDiscoverySafetyEvidence.PASSIVE_SELECTOR_BOOTSTRAP_UNKNOWN,
            safetyAuthorization = bootstrap,
            finalizationAuthority = null,
            finalizationSafetyAuthorization = null,
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.beginDiscoveryCaptureAt(
                active.copy(
                    sessionId = "selector-bootstrap-stale-raw",
                    safetyAuthorization = bootstrap.copy(
                        rawCanReceivedAtEpochMillis = TEST_NOW_EPOCH_MILLIS - 5_001,
                    ),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        store.beginDiscoveryCaptureAt(active, TEST_NOW_EPOCH_MILLIS)

        fun marker(
            markerId: String,
            definitionIndex: Int,
            evidenceAnchor: AndroidDiscoveryEvidenceAnchor,
            markerAuthorization: AndroidDiscoverySafetyAuthorization =
                bootstrapAuthorization(evidenceAnchor),
        ): AndroidDiscoveryMarkerRecord {
            val definition = template.markers[definitionIndex]
            return AndroidDiscoveryMarkerRecord(
                markerId = markerId,
                captureSessionId = active.sessionId,
                eventType = definition.eventType,
                label = definition.label,
                kind = definition.kind,
                value = null,
                unit = null,
                observedAt = "2026-08-22T00:00:0${definitionIndex + 1}Z",
                elapsedRealtimeNanos = 110L + definitionIndex,
                observedBootId = "boot-instrumentation",
                evidenceAnchor = evidenceAnchor,
                observer = "owner",
                note = null,
                safetyAuthorization = markerAuthorization,
            ).validate()
        }
        val firstMarker = marker(
            markerId = "selector-marker-instrumentation",
            definitionIndex = 0,
            evidenceAnchor = persistRawAnchor(store, scope, sessionId = 73u, sequence = 101UL),
        )
        store.appendDiscoveryMarkerAt(firstMarker, TEST_NOW_EPOCH_MILLIS)
        assertThrows(Exception::class.java) {
            store.appendDiscoveryMarkerAt(firstMarker, TEST_NOW_EPOCH_MILLIS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                marker(
                    markerId = "selector-marker-nonadvancing-anchor",
                    definitionIndex = 1,
                    evidenceAnchor = requireNotNull(firstMarker.evidenceAnchor),
                ),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        val wrongSessionAnchor = firstAnchor.copy(canSessionId = 74u)
        assertThrows(IllegalArgumentException::class.java) {
            store.appendDiscoveryMarkerAt(
                marker(
                    markerId = "selector-wrong-session",
                    definitionIndex = 1,
                    evidenceAnchor = wrongSessionAnchor,
                    markerAuthorization = bootstrapAuthorization(wrongSessionAnchor),
                ),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        template.markers.indices.drop(1).forEach { index ->
            val markerAnchor = persistRawAnchor(
                store,
                scope,
                sessionId = 73u,
                sequence = 101UL + index.toULong(),
            )
            store.appendDiscoveryMarkerAt(
                marker("selector-marker-$index", index, markerAnchor),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        val finalObservation = canObservation(sessionId = 73u, sequence = 107UL)
        assertTrue(persistRawObservation(store, scope, finalObservation))
        val finalAnchor = anchor(finalObservation)
        val finalAuthorization = bootstrapAuthorization(finalAnchor).copy(healthFrameSequence = 91UL)
        val completed = active.copy(
            state = AndroidCaptureDraftState.COMPLETED,
            endedAt = "2026-08-22T00:00:07Z",
            endedElapsedRealtimeNanos = 120,
            endedBootId = "boot-instrumentation",
            endAnchor = finalAnchor,
            endLogicalFrameCount = 8,
            endCanObservationCount = 8,
            finalizationAuthority =
                AndroidCaptureFinalizationAuthority.PASSIVE_BOOTSTRAP_VERIFIED_COMPLETION,
            finalizationSafetyAuthorization = finalAuthorization,
        ).validate()
        val anchorBeforeLastMarker = anchor(canObservation(sessionId = 73u, sequence = 105UL))
        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeDiscoveryCaptureAt(
                completed.copy(
                    endAnchor = anchorBeforeLastMarker,
                    finalizationSafetyAuthorization =
                        bootstrapAuthorization(anchorBeforeLastMarker).copy(healthFrameSequence = 91UL),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        val lastMarkerAnchor = anchor(canObservation(sessionId = 73u, sequence = 106UL))
        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeDiscoveryCaptureAt(
                completed.copy(
                    endAnchor = lastMarkerAnchor,
                    finalizationSafetyAuthorization =
                        bootstrapAuthorization(lastMarkerAnchor).copy(healthFrameSequence = 91UL),
                ).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeDiscoveryCaptureAt(
                completed.copy(endedElapsedRealtimeNanos = 115).validate(),
                TEST_NOW_EPOCH_MILLIS,
            )
        }
        store.finalizeDiscoveryCaptureAt(
            completed,
            TEST_NOW_EPOCH_MILLIS,
        )

        val saved = store.recentDiscoveryCaptures().single().session
        assertEquals(
            AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP,
            saved.safetyAuthorization.mutationAuthority,
        )
        assertEquals(73u, saved.safetyAuthorization.captureSessionId)
        assertEquals(finalAnchor, saved.endAnchor)
        assertEquals(template.markers.size, store.eventMarkers(saved.sessionId).size)
        assertEquals(
            (101UL..106UL).toList(),
            store.eventMarkers(saved.sessionId).map { requireNotNull(it.evidenceAnchor).sourceSequence },
        )
    }

    @Test
    fun recoveredV2ImportStaysHistoricalCannotGateControlsAndCannotLeaveThroughV1() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val recoveredObservation = canObservation(sessionId = 81u, sequence = 501UL)
        val bundle = recoveredV2Bundle(recoveredObservation)

        assertEquals(1, store.importBundle(bundle, scope, Instant.parse("2026-08-22T00:00:05Z")))
        assertEquals(
            DiscoveryEvidenceProvenance.RECOVERED_V2_HISTORY,
            store.recentCanObservations(scope).single().provenance.classification,
        )
        assertEquals(1, store.discoveryEvidenceSummary(scope).recoveredV2CanObservations)
        assertEquals(0, store.discoveryEvidenceSummary(scope).localAuthorizedCanObservations)
        assertEquals(0, store.evidenceCounts(scope).logicalFrames)
        assertEquals(0, store.evidenceCounts(scope).canObservations)
        assertNull(store.latestDiscoveryEvidenceAnchor(scope))
        assertFalse(store.containsDiscoveryEvidenceAnchor(scope, anchor(recoveredObservation)))
        assertTrue(store.recentPortableFramesForV1Export(scope).isEmpty())

        store.readableDatabase.query(
            "import_receipts",
            arrayOf(
                "bundle_contract_version",
                "recovery_classification",
                "source_ledger_sha256",
                "vehicle_claims_authorized",
            ),
            "bundle_id = ? AND manifest_sha256 = ?",
            arrayOf(bundle.manifest.bundleId, bundle.manifestSha256),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(EvidenceBundleManifest.CONTRACT_VERSION_V2, cursor.getString(0))
            assertEquals(RecoveryEvidenceMetadata.CLASSIFICATION, cursor.getString(1))
            assertEquals(bundle.recoveryMetadata?.sourceLedgerSha256, cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
        }
        listOf("logical_frames", "can_observations").forEach { table ->
            store.readableDatabase.query(
                table,
                arrayOf(
                    "import_bundle_id",
                    "import_manifest_sha256",
                    "import_contract_version",
                    "recovery_classification",
                    "source_ledger_sha256",
                    "vehicle_claims_authorized",
                ),
                "source_id = ?",
                arrayOf(SOURCE_ID),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(bundle.manifest.bundleId, cursor.getString(0))
                assertEquals(bundle.manifestSha256, cursor.getString(1))
                assertEquals(EvidenceBundleManifest.CONTRACT_VERSION_V2, cursor.getString(2))
                assertEquals(RecoveryEvidenceMetadata.CLASSIFICATION, cursor.getString(3))
                assertEquals(bundle.recoveryMetadata?.sourceLedgerSha256, cursor.getString(4))
                assertEquals(0, cursor.getInt(5))
            }
        }

        val importedAnchor = anchor(recoveredObservation)
        val template = AndroidDiscoveryTestLibrary.requireTemplate(
            AndroidDiscoveryTestLibrary.PARK_SELECTOR_BOOTSTRAP_TEMPLATE_ID,
            AndroidDiscoveryTestLibrary.CONTRACT_VERSION,
        )
        val importedAuthorization = bootstrapAuthorization(importedAnchor)
        val controlAttempt = AndroidDiscoveryCaptureDraft(
            sessionId = "recovered-v2-control-attempt",
            vehicleScopeId = scope.vehicleScopeId,
            vehicleProfileRevisionId = scope.vehicleProfileRevisionId,
            sourceId = scope.sourceId,
            testTemplateId = template.templateId,
            testTemplateVersion = template.version,
            testTemplateSnapshot = template,
            state = AndroidCaptureDraftState.ACTIVE,
            startedAt = "2026-08-22T00:00:06Z",
            startedElapsedRealtimeNanos = 100,
            startedBootId = "boot-recovery-test",
            endedAt = null,
            endedElapsedRealtimeNanos = null,
            endedBootId = null,
            startAnchor = importedAnchor,
            endAnchor = null,
            startLogicalFrameCount = 0,
            startCanObservationCount = 0,
            endLogicalFrameCount = null,
            endCanObservationCount = null,
            safetyEvidence = AndroidDiscoverySafetyEvidence.PASSIVE_SELECTOR_BOOTSTRAP_UNKNOWN,
            safetyAuthorization = importedAuthorization,
            finalizationAuthority = null,
            finalizationSafetyAuthorization = null,
        ).validate()
        assertThrows(IllegalArgumentException::class.java) {
            store.beginDiscoveryCaptureAt(controlAttempt, TEST_NOW_EPOCH_MILLIS)
        }
        assertNull(store.activeDiscoveryCapture())
    }

    @Test
    fun v1PortableSyncRemainsHistoricalAndTransitivelyExportable() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = canObservation(sessionId = 82u, sequence = 601UL)
        val bundle = EvidenceBundles.fromByteArray(portableV1Archive(observation))

        assertEquals(1, store.importBundle(bundle, scope, Instant.parse("2026-08-22T00:00:05Z")))
        assertEquals(
            DiscoveryEvidenceProvenance.IMPORTED_V1_HISTORY,
            store.recentCanObservations(scope).single().provenance.classification,
        )
        assertEquals(1, store.discoveryEvidenceSummary(scope).importedV1CanObservations)
        assertEquals(0, store.discoveryEvidenceSummary(scope).localAuthorizedCanObservations)
        assertNull(store.latestDiscoveryEvidenceAnchor(scope))
        assertFalse(store.containsDiscoveryEvidenceAnchor(scope, anchor(observation)))
        assertEquals(listOf("601"), store.recentPortableFramesForV1Export(scope).map { it.sourceSequence })
        store.readableDatabase.query(
            "import_receipts",
            arrayOf("bundle_contract_version", "recovery_classification", "vehicle_claims_authorized"),
            "bundle_id = ?",
            arrayOf(bundle.manifest.bundleId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(EvidenceBundleManifest.CONTRACT_VERSION_V1, cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0, cursor.getInt(2))
        }
    }

    @Test
    fun locallyPersistedRetainedLogKeepsParentOriginButCannotBootstrapLiveAuthority() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = canObservation(sessionId = 84u, sequence = 651UL)
        val frame = captureLogFrame(observation, outerSequence = 7_001UL)

        assertTrue(store.persistFrame(scope, DeviceRole.OBD_CAN, frame, frame.encode()))
        assertTrue(store.persistCanObservation(scope, observation, parentFrame = frame))

        val persisted = store.recentCanObservations(scope).single()
        assertEquals(MessageType.CAPTURE_LOG_CHUNK, persisted.originMessageType)
        assertTrue(requireNotNull(persisted.parentLogicalFrameId) > 0)
        assertNull(store.latestDiscoveryEvidenceAnchor(scope))
        assertFalse(store.containsDiscoveryEvidenceAnchor(scope, anchor(observation)))
    }

    @Test
    fun migrationV6BackfillsOnlyDefiniteLocalRowsAndKeepsImportedScopesIsolated() {
        createLegacyV6AuthorityFixture()

        val store = EvidenceDatabase.open(context)
        val local = DiscoveryEvidenceScope("vehicle-local", "profile-local", "source-local")
        val imported = DiscoveryEvidenceScope("vehicle-imported", "profile-imported", "source-imported")
        val mixed = DiscoveryEvidenceScope("vehicle-mixed", "profile-mixed", "source-local")
        val roleCollision = DiscoveryEvidenceScope(
            "vehicle-role-collision",
            "profile-role-collision",
            "source-local",
        )

        assertEquals(12, store.readableDatabase.version)
        assertEquals(EvidenceCounts(1, 1), store.evidenceCounts(local))
        assertEquals(EvidenceCounts(0, 0), store.evidenceCounts(imported))
        assertEquals(EvidenceCounts(0, 0), store.evidenceCounts(mixed))
        assertEquals(EvidenceCounts(0, 0), store.evidenceCounts(roleCollision))
        // The v6 row has no durable RAW_CAN parent envelope. v8 preserves it for history but does
        // not guess origin or allow it to authorize a live bootstrap anchor.
        assertNull(store.latestDiscoveryEvidenceAnchor(local))
        assertNull(store.latestDiscoveryEvidenceAnchor(imported))
        assertNull(store.latestDiscoveryEvidenceAnchor(mixed))
        assertNull(store.latestDiscoveryEvidenceAnchor(roleCollision))
        assertEquals(listOf("701"), store.recentPortableFramesForV1Export(local).map { it.sourceSequence })
        assertTrue(store.recentPortableFramesForV1Export(imported).isEmpty())
        assertTrue(store.recentPortableFramesForV1Export(mixed).isEmpty())
        assertTrue(store.recentPortableFramesForV1Export(roleCollision).isEmpty())

        store.readableDatabase.rawQuery(
            "SELECT bundle_contract_version, vehicle_claims_authorized FROM import_receipts ORDER BY bundle_id",
            emptyArray(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            do {
                assertEquals(EvidenceBundleManifest.CONTRACT_VERSION_V1, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            } while (cursor.moveToNext())
        }
        val indexes = mutableMapOf<String, String>()
        store.readableDatabase.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name IN " +
                "('logical_frames_v1_export', 'can_observations_live_anchor')",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) indexes[cursor.getString(0)] = cursor.getString(1)
        }
        assertTrue(indexes.getValue("logical_frames_v1_export").contains("recovery_classification IS NULL"))
        assertTrue(indexes.getValue("logical_frames_v1_export").contains("import_contract_version = '1.0.0'"))
        assertTrue(indexes.getValue("can_observations_live_anchor").contains("vehicle_claims_authorized = 1"))
        assertTrue(indexes.getValue("can_observations_live_anchor").contains("import_manifest_sha256 IS NULL"))
        assertTrue(indexes.getValue("can_observations_live_anchor").contains("origin_message_type = 2"))
        assertTrue(indexes.getValue("can_observations_live_anchor").contains("parent_logical_frame_id IS NOT NULL"))
        store.readableDatabase.rawQuery(
            "PRAGMA table_info(discovery_event_markers)",
            emptyArray(),
        ).use { cursor ->
            var observedBootIdPresent = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "observed_boot_id") observedBootIdPresent = true
            }
            assertTrue(observedBootIdPresent)
        }
    }

    @Test
    fun exactDuplicateImportsAreIdempotentAndBothReceiptsCommit() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = canObservation(sessionId = 91u, sequence = 701UL)
        val first = EvidenceBundles.fromByteArray(
            portableV1Archive(observation, bundleId = UUID.fromString("11111111-2222-4333-8444-555555555555")),
        )
        val second = EvidenceBundles.fromByteArray(
            portableV1Archive(observation, bundleId = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")),
        )

        assertEquals(1, store.importBundle(first, scope))
        assertEquals(0, store.importBundle(second, scope))
        assertEquals(EvidenceCounts(1, 1), store.counts())
        assertEquals(2L, scalar(store, "SELECT COUNT(*) FROM import_receipts"))
    }

    @Test
    fun identicalPhysicalCanInDifferentEnvelopesIsIdempotentWithoutDroppingLogicalReceipts() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = canObservation(sessionId = 96u, sequence = 706UL)
        val first = EvidenceBundles.fromByteArray(
            portableV1Archive(
                observation,
                bundleId = UUID.fromString("40000000-0000-4000-8000-000000000001"),
                outerSequence = 9_301UL,
                outerMonotonicMicroseconds = 93_010UL,
            ),
        )
        val second = EvidenceBundles.fromByteArray(
            portableV1Archive(
                observation,
                bundleId = UUID.fromString("40000000-0000-4000-8000-000000000002"),
                outerSequence = 9_302UL,
                outerMonotonicMicroseconds = 93_020UL,
            ),
        )

        assertEquals(1, store.importBundle(first, scope))
        assertEquals(1, store.importBundle(second, scope))
        assertEquals(2L, scalar(store, "SELECT COUNT(*) FROM logical_frames"))
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM can_observations"))
        assertEquals(2L, scalar(store, "SELECT COUNT(*) FROM import_receipts"))
    }

    @Test
    fun contradictoryLogicalIdentityRollsBackWithoutAReceipt() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val firstObservation = canObservation(sessionId = 92u, sequence = 702UL)
        val changedObservation = firstObservation.copy(
            data = firstObservation.data.copyOf().also { it[0] = 0x55 },
        )
        val first = EvidenceBundles.fromByteArray(
            portableV1Archive(
                firstObservation,
                bundleId = UUID.fromString("10000000-0000-4000-8000-000000000001"),
                outerSequence = 9_001UL,
                outerMonotonicMicroseconds = 90_010UL,
            ),
        )
        val contradiction = EvidenceBundles.fromByteArray(
            portableV1Archive(
                changedObservation,
                bundleId = UUID.fromString("10000000-0000-4000-8000-000000000002"),
                outerSequence = 9_001UL,
                outerMonotonicMicroseconds = 90_010UL,
            ),
        )

        assertEquals(1, store.importBundle(first, scope))
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.importBundle(contradiction, scope)
        }
        assertTrue(error.message.orEmpty().contains("Contradictory logical-frame evidence"))
        assertEquals(EvidenceCounts(1, 1), store.counts())
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM import_receipts"))
    }

    @Test
    fun contradictoryLogicalMessageTypeUsesOnePhysicalIdentityAndRollsBackWithoutReceipt() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = canObservation(sessionId = 95u, sequence = 705UL)
        val first = EvidenceBundles.fromByteArray(
            portableV1Archive(
                observation,
                bundleId = UUID.fromString("30000000-0000-4000-8000-000000000001"),
                outerSequence = 9_201UL,
                outerMonotonicMicroseconds = 92_010UL,
            ),
        )
        val contradiction = EvidenceBundles.fromByteArray(
            portableV1Archive(
                observation,
                bundleId = UUID.fromString("30000000-0000-4000-8000-000000000002"),
                outerSequence = 9_201UL,
                outerMonotonicMicroseconds = 92_010UL,
                messageType = MessageType.GATEWAY_HEALTH,
            ),
        )

        assertEquals(1, store.importBundle(first, scope))
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.importBundle(contradiction, scope)
        }

        assertTrue(error.message.orEmpty().contains("Contradictory logical-frame evidence"))
        assertEquals(EvidenceCounts(1, 1), store.counts())
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM import_receipts"))
    }

    @Test
    fun contradictoryCanIdentityRollsBackNewLogicalFrameAndReceipt() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val firstObservation = canObservation(sessionId = 93u, sequence = 703UL)
        val changedObservation = firstObservation.copy(
            data = firstObservation.data.copyOf().also { it[1] = 0x33 },
        )
        val first = EvidenceBundles.fromByteArray(
            portableV1Archive(
                firstObservation,
                bundleId = UUID.fromString("20000000-0000-4000-8000-000000000001"),
                outerSequence = 9_101UL,
                outerMonotonicMicroseconds = 91_010UL,
            ),
        )
        val contradiction = EvidenceBundles.fromByteArray(
            portableV1Archive(
                changedObservation,
                bundleId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
                outerSequence = 9_102UL,
                outerMonotonicMicroseconds = 91_020UL,
            ),
        )

        assertEquals(1, store.importBundle(first, scope))
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.importBundle(contradiction, scope)
        }
        assertTrue(error.message.orEmpty().contains("Contradictory CAN evidence"))
        assertEquals(EvidenceCounts(1, 1), store.counts())
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM import_receipts"))
    }

    @Test
    fun sourceRoleCollisionFailsClosedAndCannotTransitivelyExport() {
        val store = EvidenceDatabase.open(context)
        val scope = seedVehicleAndSource(store, PROFILE_ONE)
        val observation = canObservation(sessionId = 94u, sequence = 704UL)
        val collision = EvidenceBundles.fromByteArray(
            portableV1Archive(
                observation,
                bundleId = UUID.fromString("30000000-0000-4000-8000-000000000001"),
                sourceRole = DeviceRole.AC_SENSOR,
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            store.importBundle(collision, scope)
        }
        assertTrue(error.message.orEmpty().contains("source role conflicts"))
        assertEquals(EvidenceCounts(0, 0), store.counts())
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM import_receipts"))
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertValidatedSource(
                PersistedSource(
                    sourceId = SOURCE_ID,
                    role = DeviceRole.AC_SENSOR,
                    bluetoothAddress = "00:11:22:33:44:55",
                    identityJson = "{}",
                    validatedAt = "2026-08-22T00:00:06Z",
                ),
            )
        }

        val valid = EvidenceBundles.fromByteArray(portableV1Archive(observation))
        assertEquals(1, store.importBundle(valid, scope))
        store.writableDatabase.execSQL(
            "UPDATE logical_frames SET source_role = 'AC_SENSOR' WHERE source_id = ?",
            arrayOf(SOURCE_ID),
        )
        assertTrue(store.recentPortableFramesForV1Export(scope).isEmpty())
    }

    private fun seedVehicleAndSource(
        store: EvidenceDatabase,
        revisionId: String,
    ): DiscoveryEvidenceScope {
        store.appendVehicleProfile(
            VehicleProfile(
                revisionId = revisionId,
                createdAt = "2026-08-22T00:00:00Z",
            ).validate()
        )
        store.upsertValidatedSource(
            PersistedSource(
                sourceId = SOURCE_ID,
                role = DeviceRole.OBD_CAN,
                bluetoothAddress = "00:11:22:33:44:55",
                identityJson = "{\"test\":true}",
                validatedAt = "2026-08-22T00:00:00Z",
            )
        )
        return requireNotNull(store.resolveDiscoveryEvidenceScope(SOURCE_ID))
    }

    private val authorization = AndroidDiscoverySafetyAuthorization(
        sourceId = SOURCE_ID,
        healthFrameSequence = 7UL,
        healthGatewayMonotonicMicroseconds = 7_000UL,
        receivedAtEpochMillis = 1_777_000_000_000L,
        listenOnlyProven = true,
    )

    private fun canObservation(sessionId: UInt, sequence: ULong) = CanObservation(
        sessionId = sessionId,
        sourceSequence = sequence,
        monotonicMicroseconds = sequence * 1_000UL,
        bitrateBps = 500_000,
        identifier = 0x2C4u,
        extended = false,
        remoteRequest = false,
        listenOnly = true,
        dataLength = 8,
        data = byteArrayOf(0x15, 0x6C, 0, 0, 0, 0, 0, 0),
    )

    private fun persistRawObservation(
        store: EvidenceDatabase,
        scope: DiscoveryEvidenceScope,
        observation: CanObservation,
        outerSequence: ULong = observation.sourceSequence,
        outerMonotonicMicroseconds: ULong = observation.monotonicMicroseconds,
    ): Boolean {
        val frame = GatewayFrame(
            messageType = MessageType.RAW_CAN_FRAME,
            sequence = outerSequence,
            monotonicMicroseconds = outerMonotonicMicroseconds,
            payload = observation.encodeLive(),
        )
        store.persistFrame(scope, DeviceRole.OBD_CAN, frame, frame.encode())
        return store.persistCanObservation(scope, observation, parentFrame = frame)
    }

    private fun persistRawAnchor(
        store: EvidenceDatabase,
        scope: DiscoveryEvidenceScope,
        sessionId: UInt,
        sequence: ULong,
    ): AndroidDiscoveryEvidenceAnchor {
        val observation = canObservation(sessionId, sequence)
        assertTrue(persistRawObservation(store, scope, observation))
        return anchor(observation)
    }

    private fun captureLogFrame(
        observation: CanObservation,
        outerSequence: ULong,
    ): GatewayFrame {
        val live = observation.encodeLive()
        val stored = ByteArray(CanObservation.RECORD_BYTES)
        live.copyInto(stored, endIndex = 24)
        observation.data.copyInto(stored, destinationOffset = 24)
        stored.putLittleEndianUInt(32, Crc32c.checksum(stored, 0, 32))
        val payload = ByteArray(16 + stored.size).apply {
            this[0] = 1
            this[1] = 0
            this[2] = 1
            putLittleEndianUInt(4, 0u)
            putLittleEndianUShort(8, 1)
            putLittleEndianUShort(10, CanObservation.RECORD_BYTES)
            putLittleEndianUInt(12, observation.sessionId)
            stored.copyInto(this, destinationOffset = 16)
        }
        return GatewayFrame(
            messageType = MessageType.CAPTURE_LOG_CHUNK,
            sequence = outerSequence,
            monotonicMicroseconds = outerSequence * 10UL,
            payload = payload,
        )
    }

    private fun ByteArray.putLittleEndianUInt(offset: Int, value: UInt) {
        repeat(4) { index -> this[offset + index] = (value shr (index * 8)).toByte() }
    }

    private fun ByteArray.putLittleEndianUShort(offset: Int, value: Int) {
        require(value in 0..0xFFFF)
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun anchor(observation: CanObservation) = AndroidDiscoveryEvidenceAnchor(
        sourceId = SOURCE_ID,
        canSessionId = observation.sessionId,
        sourceSequence = observation.sourceSequence,
        gatewayMonotonicMicroseconds = observation.monotonicMicroseconds,
    ).validate()

    private fun bootstrapAuthorization(rawAnchor: AndroidDiscoveryEvidenceAnchor) =
        AndroidDiscoverySafetyAuthorization(
            sourceId = SOURCE_ID,
            healthFrameSequence = 90UL,
            healthGatewayMonotonicMicroseconds = 90_000UL,
            receivedAtEpochMillis = 1_777_000_000_000L,
            mutationAuthority = AndroidDiscoveryMutationAuthority.PASSIVE_PARK_SELECTOR_BOOTSTRAP,
            healthVehicleMotion = VehicleMotion.UNKNOWN,
            captureSessionId = rawAnchor.canSessionId,
            rawCanAnchor = rawAnchor,
            rawCanReceivedAtEpochMillis = 1_777_000_000_100L,
            listenOnlyProven = true,
            captureActiveProven = true,
            requiredCapability = AndroidDiscoveryTestLibrary.PASSIVE_CAPTURE_CAPABILITY,
        ).validate()

    private fun createLegacyV6AuthorityFixture() {
        val file = context.getDatabasePath(EvidenceDatabase.DATABASE_NAME)
        file.parentFile?.mkdirs()
        PlatformSQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE sources (source_id TEXT PRIMARY KEY NOT NULL, role TEXT NOT NULL, " +
                    "bluetooth_address TEXT NOT NULL, identity_json TEXT NOT NULL, validated_at TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE logical_frames (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "vehicle_scope_id TEXT NOT NULL, vehicle_profile_revision_id TEXT NOT NULL, " +
                    "source_id TEXT NOT NULL, source_role TEXT NOT NULL, source_sequence TEXT NOT NULL, " +
                    "source_monotonic_us TEXT NOT NULL, protocol_major INTEGER NOT NULL, " +
                    "protocol_minor INTEGER NOT NULL, message_type INTEGER NOT NULL, flags INTEGER NOT NULL, " +
                    "envelope BLOB NOT NULL, envelope_sha256 TEXT NOT NULL, ingested_at TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE can_observations (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "vehicle_scope_id TEXT NOT NULL, vehicle_profile_revision_id TEXT NOT NULL, " +
                    "source_id TEXT NOT NULL, session_id TEXT NOT NULL, source_sequence TEXT NOT NULL, " +
                    "source_monotonic_us TEXT NOT NULL, bitrate_bps INTEGER NOT NULL, identifier INTEGER NOT NULL, " +
                    "extended INTEGER NOT NULL, remote_request INTEGER NOT NULL, listen_only INTEGER NOT NULL, " +
                    "data_length INTEGER NOT NULL, data BLOB NOT NULL, ingested_at TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE import_receipts (bundle_id TEXT NOT NULL, manifest_sha256 TEXT NOT NULL, " +
                    "vehicle_scope_id TEXT NOT NULL, vehicle_profile_revision_id TEXT NOT NULL, " +
                    "imported_at TEXT NOT NULL, record_count INTEGER NOT NULL, " +
                    "PRIMARY KEY(bundle_id, manifest_sha256, vehicle_scope_id, vehicle_profile_revision_id))"
            )
            // A real v6 store already contains this table. Keep the fixture structurally faithful
            // so the v8 -> v9 boot-lineage migration exercises a supported database topology.
            database.execSQL(
                "CREATE TABLE discovery_event_markers (marker_id TEXT PRIMARY KEY NOT NULL, " +
                    "capture_session_id TEXT NOT NULL, event_type TEXT NOT NULL, label TEXT NOT NULL, " +
                    "marker_kind TEXT NOT NULL, value_text TEXT, unit TEXT, observed_at TEXT NOT NULL, " +
                    "elapsed_realtime_nanos TEXT NOT NULL, source_id TEXT, can_session_id TEXT, " +
                    "nearest_source_sequence TEXT, nearest_gateway_monotonic_us TEXT, " +
                    "observer TEXT NOT NULL, note TEXT, safety_authorization_source_id TEXT NOT NULL, " +
                    "safety_authorization_frame_sequence TEXT NOT NULL, " +
                    "safety_authorization_gateway_monotonic_us TEXT NOT NULL, " +
                    "safety_authorization_received_at_ms INTEGER NOT NULL, " +
                    "safety_authorization_extension_json TEXT)"
            )
            database.execSQL(
                "INSERT INTO sources VALUES " +
                    "('source-local', 'OBD_CAN', 'AA:BB:CC:DD:EE:FF', '{}', '2026-08-21T00:00:00Z'), " +
                    "('source-imported', 'OBD_CAN', 'IMPORTED', '{}', '2026-08-21T00:00:00Z')"
            )
            listOf(
                LegacyAuthorityRow("vehicle-local", "profile-local", "source-local", 701),
                LegacyAuthorityRow("vehicle-imported", "profile-imported", "source-imported", 702),
                LegacyAuthorityRow("vehicle-mixed", "profile-mixed", "source-local", 703),
                LegacyAuthorityRow(
                    "vehicle-role-collision",
                    "profile-role-collision",
                    "source-local",
                    704,
                ),
            ).forEach { row -> insertLegacyAuthorityRow(database, row) }
            database.execSQL(
                "UPDATE logical_frames SET source_role = 'AC_SENSOR' " +
                    "WHERE vehicle_scope_id = 'vehicle-role-collision'",
            )
            database.execSQL(
                "INSERT INTO import_receipts VALUES " +
                    "('bundle-imported', '${"a".repeat(64)}', 'vehicle-imported', 'profile-imported', " +
                    "'2026-08-21T00:00:02Z', 1), " +
                    "('bundle-mixed', '${"b".repeat(64)}', 'vehicle-mixed', 'profile-mixed', " +
                    "'2026-08-21T00:00:02Z', 1)"
            )
            database.version = 6
        }
    }

    private fun insertLegacyAuthorityRow(
        database: PlatformSQLiteDatabase,
        row: LegacyAuthorityRow,
    ) {
        val frame = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = row.sequence.toULong(),
            monotonicMicroseconds = (row.sequence * 10).toULong(),
            payload = "{}".toByteArray(),
        )
        val envelope = frame.encode()
        database.execSQL(
            "INSERT INTO logical_frames(vehicle_scope_id, vehicle_profile_revision_id, source_id, " +
                "source_role, source_sequence, source_monotonic_us, protocol_major, protocol_minor, " +
                "message_type, flags, envelope, envelope_sha256, ingested_at) " +
                "VALUES (?, ?, ?, 'OBD_CAN', ?, ?, 1, 0, 4, 0, ?, ?, '2026-08-21T00:00:01Z')",
            arrayOf(
                row.vehicleScopeId, row.profileRevisionId, row.sourceId, row.sequence.toString(),
                (row.sequence * 10).toString(), envelope, EvidenceBundles.sha256(envelope),
            ),
        )
        database.execSQL(
            "INSERT INTO can_observations(vehicle_scope_id, vehicle_profile_revision_id, source_id, " +
                "session_id, source_sequence, source_monotonic_us, bitrate_bps, identifier, extended, " +
                "remote_request, listen_only, data_length, data, ingested_at) " +
                "VALUES (?, ?, ?, '9', ?, ?, 500000, 291, 0, 0, 1, 1, ?, '2026-08-21T00:00:01Z')",
            arrayOf(
                row.vehicleScopeId, row.profileRevisionId, row.sourceId, row.sequence.toString(),
                (row.sequence * 10).toString(), byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0),
            ),
        )
    }

    private fun portableV1Archive(
        observation: CanObservation,
        bundleId: UUID = UUID.fromString("83b18c79-8275-4fb2-9722-9056fa93dd3b"),
        sourceRole: DeviceRole = DeviceRole.OBD_CAN,
        outerSequence: ULong = observation.sourceSequence,
        outerMonotonicMicroseconds: ULong = observation.monotonicMicroseconds,
        messageType: MessageType = MessageType.RAW_CAN_FRAME,
    ): ByteArray {
        val frame = GatewayFrame(
            messageType = messageType,
            sequence = outerSequence,
            monotonicMicroseconds = outerMonotonicMicroseconds,
            payload = if (messageType == MessageType.RAW_CAN_FRAME) {
                observation.encodeLive()
            } else {
                "{}".toByteArray()
            },
        )
        val envelope = frame.encode()
        return EvidenceBundles.toByteArray(
            records = listOf(
                PortableEvidenceRecord(
                    sourceRole = sourceRole.wireValue,
                    sourceId = SOURCE_ID,
                    sourceSequence = frame.sequence.toString(),
                    sourceMonotonicMicroseconds = frame.monotonicMicroseconds.toString(),
                    protocolMajor = frame.protocolMajor,
                    protocolMinor = frame.protocolMinor,
                    messageType = frame.messageType.code,
                    flags = frame.flags,
                    ingestedAt = "2026-08-22T00:00:04Z",
                    envelopeSha256 = EvidenceBundles.sha256(envelope),
                    envelopeBase64 = Base64.getEncoder().encodeToString(envelope),
                )
            ),
            creator = BundleCreator("IOS", "com.example.vhos", "2.0.0", "iPhone"),
            bundleId = bundleId,
            createdAt = Instant.parse("2026-08-22T00:00:04Z"),
        )
    }

    private fun scalar(store: EvidenceDatabase, sql: String): Long =
        store.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun recoveredV2Bundle(observation: CanObservation) =
        portableV1Archive(observation).let { v1Archive ->
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(v1Archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val manifest = JsonParser.parseString(
            entries.getValue("manifest.json").toString(Charsets.UTF_8),
        ).asJsonObject
        manifest.addProperty("contract_version", EvidenceBundleManifest.CONTRACT_VERSION_V2)
        val ledgerSha256 = manifest.getAsJsonArray("segments").single().asJsonObject
            .get("sha256").asString
        manifest.add(
            "recovery",
            JsonObject().apply {
                addProperty("classification", RecoveryEvidenceMetadata.CLASSIFICATION)
                addProperty("vehicle_claims_authorized", false)
                addProperty("source_ledger_sha256", ledgerSha256)
            },
        )
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        val v2Archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        EvidenceBundles.fromByteArray(v2Archive)
        }

    companion object {
        private data class LegacyAuthorityRow(
            val vehicleScopeId: String,
            val profileRevisionId: String,
            val sourceId: String,
            val sequence: Int,
        )

        private const val TEST_NOW_EPOCH_MILLIS = 1_777_000_000_500L
        private const val SOURCE_ID = "gateway-instrumentation"
        private const val PROFILE_ONE = "11111111-1111-4111-8111-111111111111"
        private const val PROFILE_TWO = "22222222-2222-4222-8222-222222222222"
    }
}
