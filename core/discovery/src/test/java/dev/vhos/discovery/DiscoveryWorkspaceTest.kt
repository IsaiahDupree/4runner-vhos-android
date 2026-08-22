package dev.vhos.discovery

import dev.vhos.model.ConnectionPhase
import dev.vhos.model.DeviceRole
import dev.vhos.model.DeviceSnapshot
import dev.vhos.model.IndicatorLevel
import dev.vhos.model.VehicleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryWorkspaceTest {
    @Test
    fun testLibraryIsVersionedCanonicalAndSafetyClassified() {
        val templates = AndroidDiscoveryTestLibrary.templates

        assertEquals(15, templates.size)
        assertEquals(templates.size, templates.map { it.templateId }.distinct().size)
        assertTrue(templates.all { it.validate() === it })
        assertTrue(templates.any { it.executionAuthority == AndroidDiscoveryExecutionAuthority.PARKED_PASSIVE })
        assertTrue(templates.any {
            it.executionAuthority == AndroidDiscoveryExecutionAuthority.PASSENGER_SUPERVISED_DRIVE
        })
        assertTrue(templates.any {
            it.executionAuthority == AndroidDiscoveryExecutionAuthority.SPECIALIST_SETUP
        })
        assertTrue(templates.flatMap { it.markers }.any {
            it.kind == AndroidDiscoveryMarkerKind.MANUAL_MEASUREMENT
        })
    }

    @Test
    fun captureSessionRequiresImmutableEvidenceCursorsAndFinalClocks() {
        val template = AndroidDiscoveryTestLibrary.requireTemplate(
            "vhos.discovery.brake-pulse",
            AndroidDiscoveryTestLibrary.CONTRACT_VERSION,
        )
        val active = AndroidDiscoveryCaptureDraft(
            sessionId = "capture-1",
            vehicleScopeId = "vehicle-scope-1",
            vehicleProfileRevisionId = "profile-1",
            sourceId = "gateway-1",
            testTemplateId = template.templateId,
            testTemplateVersion = template.version,
            testTemplateSnapshot = template,
            state = AndroidCaptureDraftState.ACTIVE,
            startedAt = "2026-08-21T12:00:00Z",
            startedElapsedRealtimeNanos = 100,
            startedBootId = "boot-1",
            endedAt = null,
            endedElapsedRealtimeNanos = null,
            endedBootId = null,
            startAnchor = anchor(20UL),
            endAnchor = null,
            startLogicalFrameCount = 10,
            startCanObservationCount = 20,
            endLogicalFrameCount = null,
            endCanObservationCount = null,
            safetyEvidence = AndroidDiscoverySafetyEvidence.VALIDATED_GATEWAY_HEALTH_PARKED,
            safetyAuthorization = authorization(),
            finalizationAuthority = null,
            finalizationSafetyAuthorization = null,
        ).validate()

        val completed = active.copy(
            state = AndroidCaptureDraftState.COMPLETED,
            endedAt = "2026-08-21T12:01:00Z",
            endedElapsedRealtimeNanos = 200,
            endedBootId = "boot-1",
            endAnchor = anchor(42UL),
            endLogicalFrameCount = 40,
            endCanObservationCount = 42,
            finalizationAuthority = AndroidCaptureFinalizationAuthority.PARKED_VERIFIED_COMPLETION,
            finalizationSafetyAuthorization = authorization(sequence = 9UL),
        ).validate()

        assertEquals(22, completed.endCanObservationCount!! - completed.startCanObservationCount)
        assertThrows(IllegalArgumentException::class.java) {
            completed.copy(endCanObservationCount = 19).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            active.copy(endedAt = "2026-08-21T12:01:00Z").validate()
        }
    }

    @Test
    fun eventMarkersRequireMeasurementValueAndRetainEvidenceAnchor() {
        val marker = AndroidDiscoveryMarkerRecord(
            markerId = "marker-1",
            captureSessionId = "capture-1",
            eventType = "measurement.ac_low_pressure",
            label = "Low-side pressure",
            kind = AndroidDiscoveryMarkerKind.MANUAL_MEASUREMENT,
            value = "34.2",
            unit = "psi",
            observedAt = "2026-08-21T12:00:02Z",
            elapsedRealtimeNanos = 120,
            evidenceAnchor = anchor(22UL),
            observer = "owner",
            note = null,
            safetyAuthorization = authorization(),
        ).validate()

        assertEquals(22UL, marker.evidenceAnchor?.sourceSequence)
        assertThrows(IllegalArgumentException::class.java) {
            marker.copy(value = null).validate()
        }
    }

    @Test
    fun crossBootRecoveryIsAbortOnlyAndArchivedTemplateSnapshotNeedsNoCatalogLookup() {
        val archived = AndroidDiscoveryTestLibrary.templates.first().copy(
            version = "9.9.9",
            title = "Archived procedure title",
        ).validate()
        val active = AndroidDiscoveryCaptureDraft(
            sessionId = "capture-from-prior-boot",
            vehicleScopeId = "vehicle-scope-1",
            vehicleProfileRevisionId = "profile-1",
            sourceId = "gateway-1",
            testTemplateId = archived.templateId,
            testTemplateVersion = archived.version,
            testTemplateSnapshot = archived,
            state = AndroidCaptureDraftState.ACTIVE,
            startedAt = "2026-08-21T12:00:00Z",
            startedElapsedRealtimeNanos = 9_000,
            startedBootId = "boot-before-restart",
            endedAt = null,
            endedElapsedRealtimeNanos = null,
            endedBootId = null,
            startAnchor = anchor(20UL),
            endAnchor = null,
            startLogicalFrameCount = 10,
            startCanObservationCount = 20,
            endLogicalFrameCount = null,
            endCanObservationCount = null,
            safetyEvidence = AndroidDiscoverySafetyEvidence.VALIDATED_GATEWAY_HEALTH_PARKED,
            safetyAuthorization = authorization(),
            finalizationAuthority = null,
            finalizationSafetyAuthorization = null,
        ).validate()

        val recovered = active.copy(
            state = AndroidCaptureDraftState.ABORTED,
            endedAt = "2026-08-21T12:01:00Z",
            endedElapsedRealtimeNanos = 100,
            endedBootId = "boot-after-restart",
            endAnchor = anchor(42UL),
            endLogicalFrameCount = 40,
            endCanObservationCount = 42,
            finalizationAuthority = AndroidCaptureFinalizationAuthority.INTERRUPTED_BY_REBOOT,
        ).validate()

        assertEquals("Archived procedure title", recovered.testTemplateSnapshot.title)
        assertThrows(IllegalArgumentException::class.java) {
            recovered.copy(
                state = AndroidCaptureDraftState.COMPLETED,
                finalizationAuthority = AndroidCaptureFinalizationAuthority.PARKED_VERIFIED_COMPLETION,
                finalizationSafetyAuthorization = authorization(9UL),
            ).validate()
        }
    }

    @Test
    fun androidCapabilityObservationPreservesUnknownWithoutInventingCapabilities() {
        val snapshot = AndroidVehicleCapabilityObservation(
            snapshotId = "capability-1",
            capturedAt = "2026-08-21T12:00:00Z",
            vehicleScopeId = "vehicle-scope-1",
            vehicleProfileRevisionId = "profile-1",
            sourceId = "gateway-1",
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
            safetyAuthorization = authorization(),
        ).validate()

        assertEquals("gateway-1", snapshot.sourceId)
        assertNull(snapshot.listenOnlyProven)
        assertNull(snapshot.obdEnumerationComplete)
        assertNull(snapshot.canCommunicationDetected)
    }

    @Test
    fun engineeringGateRequiresFreshStreamingParkedGatewayHealth() {
        val now = 1_000_000L
        val parked = device(
            phase = ConnectionPhase.STREAMING,
            motion = VehicleMotion.PARKED,
            observedAt = now - 500,
            lastFrameAt = now - 200,
        )

        val gate = AndroidDiscoveryEngineeringSafetyGate.evaluate(parked, now)
        assertTrue(gate.allowed)
        assertEquals("gateway-1", gate.authorization?.sourceId)
        assertEquals(41UL, gate.authorization?.healthFrameSequence)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(vehicleMotion = VehicleMotion.UNKNOWN), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(vehicleMotion = VehicleMotion.MOVING), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(vehicleMotionObservedAtEpochMs = now - 2_001), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(lastFrameAtEpochMs = now - 2_001), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(phase = ConnectionPhase.DEGRADED), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(vehicleMotionObservedAtEpochMs = now + 1), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(lastFrameAtEpochMs = now + 1), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(vehicleMotionObservedAtEpochMs = null), now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(lastFrameAtEpochMs = null), now,
        ).allowed)
        assertTrue(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(
                vehicleMotionObservedAtEpochMs = now - 2_000,
                lastFrameAtEpochMs = now - 2_000,
            ),
            now,
        ).allowed)
        assertFalse(AndroidDiscoveryEngineeringSafetyGate.evaluate(
            parked.copy(vehicleMotionFrameSequence = null), now,
        ).allowed)
    }

    @Test
    fun discoveryCandidatePriorityNeverBecomesConfidenceOrPromotionAuthority() {
        val candidate = AndroidCandidateResearchItem(
            candidateId = "toyota.224.brake-pressure.be16-low9",
            sourceDescription = "CAN 0x224 candidate field",
            proposedCanonicalSignalId = "brakes.pressure.candidate",
            evidenceStatus = "FIELD_PRESENT_DYNAMIC",
            retainedRecords = 12,
            captureSessions = 2,
            researchPriority = 96,
            confidence = null,
            authority = AndroidCandidateResearchItem.AUTHORITY,
            nextValidation = "Collect a synchronized independent reference.",
            promotionChecklist = AndroidSignalPromotionGate(
                signalDefinition = true,
                sourceDefined = true,
                decoderDefined = true,
                unitsAndTypeDefined = false,
                plausibleRangeDefined = false,
                freshnessRuleDefined = false,
                targetVehicleCapture = true,
                independentCorroboration = false,
                goldenReplay = false,
            ),
        ).validate()

        assertEquals(96, candidate.researchPriority)
        assertNull(candidate.confidence)
        assertFalse(candidate.promotionChecklist.ready)
    }

    private fun anchor(sequence: ULong) = AndroidDiscoveryEvidenceAnchor(
        sourceId = "gateway-1",
        canSessionId = 7u,
        sourceSequence = sequence,
        gatewayMonotonicMicroseconds = sequence * 1_000UL,
    )

    private fun authorization(sequence: ULong = 7UL) = AndroidDiscoverySafetyAuthorization(
        sourceId = "gateway-1",
        healthFrameSequence = sequence,
        healthGatewayMonotonicMicroseconds = sequence * 1_000UL,
        receivedAtEpochMillis = 999_500L,
    )

    @Test
    fun `latest moving state immediately revokes a previously parked authorization`() {
        val cachedParked = AndroidDiscoveryEngineeringSafetyGate.evaluate(
            device(ConnectionPhase.STREAMING, VehicleMotion.PARKED, 999_500L, 999_500L),
            nowEpochMillis = 1_000_000L,
        )
        val authoritativeMoving = AndroidDiscoveryEngineeringSafetyGate.evaluate(
            device(ConnectionPhase.STREAMING, VehicleMotion.MOVING, 999_600L, 999_600L),
            nowEpochMillis = 1_000_000L,
        )

        assertTrue(cachedParked.allowed)
        assertFalse(authoritativeMoving.allowed)
        assertNull(authoritativeMoving.authorization)
    }

    @Test
    fun `cumulative CAN counters never make a stopped bus current`() {
        val cumulativeOnly = device(
            ConnectionPhase.STREAMING,
            VehicleMotion.PARKED,
            observedAt = 999_500L,
            lastFrameAt = 999_500L,
        ).copy(vehicleFrames = 50_000L, lastVehicleFrameAtEpochMs = null)
        val freshRawCan = cumulativeOnly.copy(lastVehicleFrameAtEpochMs = 999_700L)

        assertFalse(AndroidDiscoveryLiveEvidence.isVehicleBusCurrent(
            cumulativeOnly, "gateway-1", nowEpochMillis = 1_000_000L,
        ))
        assertTrue(AndroidDiscoveryLiveEvidence.isVehicleBusCurrent(
            freshRawCan, "gateway-1", nowEpochMillis = 1_000_000L,
        ))
        assertFalse(AndroidDiscoveryLiveEvidence.isVehicleBusCurrent(
            freshRawCan, "another-gateway", nowEpochMillis = 1_000_000L,
        ))
    }

    private fun device(
        phase: ConnectionPhase,
        motion: VehicleMotion,
        observedAt: Long?,
        lastFrameAt: Long?,
    ) = DeviceSnapshot(
        role = DeviceRole.OBD_CAN,
        phase = phase,
        level = IndicatorLevel.PASS,
        detail = "validated test snapshot",
        sourceId = "gateway-1",
        vehicleMotion = motion,
        vehicleMotionObservedAtEpochMs = observedAt,
        vehicleMotionFrameSequence = 41UL,
        vehicleMotionGatewayMonotonicMicroseconds = 41_000UL,
        lastFrameAtEpochMs = lastFrameAt,
    )
}
