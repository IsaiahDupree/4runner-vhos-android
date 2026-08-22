package dev.vhos.store

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import dev.vhos.digitaltwin.VehicleProfile
import dev.vhos.model.DeviceRole
import dev.vhos.model.VehicleMotion
import dev.vhos.protocol.CanObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        assertEquals(6, store.readableDatabase.version)
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
        assertTrue(store.persistCanObservation(firstScope, observation))

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
        assertTrue(store.persistCanObservation(secondScope, observation))

        assertEquals(1, store.recentCanObservations(firstScope).size)
        assertEquals(1, store.recentCanObservations(secondScope).size)
        assertEquals(PROFILE_ONE, store.recentCanObservations(firstScope).single().vehicleProfileRevisionId)
        assertEquals(PROFILE_TWO, store.recentCanObservations(secondScope).single().vehicleProfileRevisionId)

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
        assertTrue(store.persistCanObservation(scope, firstObservation))
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
            startLogicalFrameCount = 0,
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
            evidenceAnchor: AndroidDiscoveryEvidenceAnchor = firstAnchor,
            markerAuthorization: AndroidDiscoverySafetyAuthorization = bootstrap,
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
                evidenceAnchor = evidenceAnchor,
                observer = "owner",
                note = null,
                safetyAuthorization = markerAuthorization,
            ).validate()
        }
        val firstMarker = marker(
            markerId = "selector-marker-instrumentation",
            definitionIndex = 0,
        )
        store.appendDiscoveryMarkerAt(firstMarker, TEST_NOW_EPOCH_MILLIS)
        assertThrows(Exception::class.java) {
            store.appendDiscoveryMarkerAt(firstMarker, TEST_NOW_EPOCH_MILLIS)
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
            store.appendDiscoveryMarkerAt(
                marker("selector-marker-$index", index),
                TEST_NOW_EPOCH_MILLIS,
            )
        }

        val finalObservation = canObservation(sessionId = 73u, sequence = 101UL)
        assertTrue(store.persistCanObservation(scope, finalObservation))
        val finalAnchor = anchor(finalObservation)
        val finalAuthorization = bootstrapAuthorization(finalAnchor).copy(healthFrameSequence = 91UL)
        store.finalizeDiscoveryCaptureAt(
            active.copy(
                state = AndroidCaptureDraftState.COMPLETED,
                endedAt = "2026-08-22T00:00:02Z",
                endedElapsedRealtimeNanos = 120,
                endedBootId = "boot-instrumentation",
                endAnchor = finalAnchor,
                endLogicalFrameCount = 0,
                endCanObservationCount = 2,
                finalizationAuthority =
                    AndroidCaptureFinalizationAuthority.PASSIVE_BOOTSTRAP_VERIFIED_COMPLETION,
                finalizationSafetyAuthorization = finalAuthorization,
            ).validate(),
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
        assertTrue(store.eventMarkers(saved.sessionId).all { it.evidenceAnchor == firstAnchor })
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

    companion object {
        private const val TEST_NOW_EPOCH_MILLIS = 1_777_000_000_500L
        private const val SOURCE_ID = "gateway-instrumentation"
        private const val PROFILE_ONE = "11111111-1111-4111-8111-111111111111"
        private const val PROFILE_TWO = "22222222-2222-4222-8222-222222222222"
    }
}
