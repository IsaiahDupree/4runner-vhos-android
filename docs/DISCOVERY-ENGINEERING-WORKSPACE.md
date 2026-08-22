# Discovery Engineering workspace

## Outcome

Android source candidate `0.1.0-dev.13` adds the first production Discovery workspace to the
head-unit app. It turns retained encrypted evidence and current validated gateway state into a
read-only engineering overview, a versioned test library, durable Android capture drafts, event
markers, candidate review, registry gates, and production-path replay.

It does **not** claim that an unknown CAN field has been decoded. It does **not** copy historical
values into a live display. It does **not** allow a research priority to become confidence. It does
**not** serialize Android operational drafts as the portable iPhone Discovery contracts.

The implemented product flow is:

```text
validated BLE contract
        |
        +-- current gateway health -------------------- safety authority
        |
        +-- append-only logical frames
                    |
                    +-- retained CAN observations ----- Discovery overview/signals
                    |                                      |
                    |                                      +-- candidate research
                    |                                      +-- read-only replay
                    |
                    +-- Android capture draft
                            +-- versioned test template
                            +-- start/end evidence cursor
                            +-- synchronized event markers
                            +-- independent measurements
```

## Entry point and layout

The main head-unit screen includes **Open Discovery Engineering**. It opens a landscape-friendly
three-pane workspace:

- navigation at left;
- the selected evidence surface in the center; and
- provenance, authority, safety, or promotion details at right.

The workspace includes these first surfaces:

| Surface | Real source | Authority |
| --- | --- | --- |
| Overview | current `HeadUnitRuntime` plus SQLCipher counts and retained analysis | observed/acquisition facts only |
| Live Signals | supported standard J1979 readings in the current BLE session | standard definition + supported-PID evidence |
| Test Library | compiled, versioned Android procedures | procedure only; not evidence that a signal exists |
| Capture Sessions | SQLCipher `discovery_capture_sessions` and event-marker rows | Android operational draft only |
| Candidate Inbox | pinned Toyota hypothesis pack evaluated against retained target bytes | Engineering candidate; no owner display |
| Signal Registry | currently supported standard signals and closed raw-CAN promotion gate | zero promoted raw-CAN signals today |
| Replay Lab | retained CAN sent through production envelope, CRC, stream, and CAN decoders | historical and read-only |
| Discovery Progress | retained counts and validation-stage counts | no invented percentage-understood score |

Every empty state stays empty or `UNKNOWN`. A historical observation is labeled historical. The
workspace never creates demo RPM, temperature, pressure, speed, confidence, or coverage values.

Each in-memory Discovery analysis and Replay Lab run is deliberately bounded to the newest 100,000
retained CAN observations from one resolved vehicle/gateway scope. The overview's retained count
comes from that scoped encrypted evidence set, while
analysis-window counts are labeled separately so the bounded window is never mistaken for the full
evidence corpus. The explicit 20× decoder-load action uses a smaller, labeled 10,000-record window
to exercise 200,000 production-path records. Replay compares decoded order and payload through a
bounded streaming oracle instead of retaining a second 200,000-record result list.

## Deterministic PARKED gate

All test, capture, marker, capability-save, and normal-completion controls fail closed unless the
current validated gateway-health stream reports `PARKED`. Read-only review/replay does not widen
that authority.

`AndroidDiscoveryEngineeringSafetyGate` requires all of the following at the same time:

1. the OBD gateway connection phase is `STREAMING`;
2. the latest validated `gateway.health` payload says `vehicle_motion=PARKED`;
3. that motion report is no more than 2 seconds old; and
4. the overall validated frame stream is no more than 2 seconds old; and
5. the exact gateway source, health-frame sequence, gateway monotonic time, and local receipt time
   are available for durable authorization lineage.

Every mutation re-reads the synchronous `HeadUnitRuntime.snapshot()` immediately before its
database write. The activity's throttled/conflated render snapshot is display-only; it can never
authorize a capture, marker, capability observation, or normal completion. A newly received
`MOVING` or `UNKNOWN` health frame therefore revokes a cached `PARKED` view before the next render.

The gate rejects:

- `UNKNOWN`;
- `MOVING`;
- a stale `PARKED` value;
- a future/invalid timestamp;
- a degraded, reconnecting, or disconnected transport;
- owner assertion; and
- vehicle speed equal to zero.

The BLE client now preserves the validated health-frame motion enum, frame sequence, gateway
monotonic time, source identity, and local receipt time in `DeviceSnapshot`. Unsupported wire values
such as `STOPPED` are protocol errors rather than new safety states. `UNKNOWN` is the default.

When PARKED evidence is lost during a capture, new markers and normal completion lock immediately.
The operator may perform a **safety abort** so the app is never trapped in an active session. Abort
is the sole mutation exempt from current PARKED authority: it only terminates recording, is stored
as `OWNER_SAFETY_ABORT`, carries no final PARKED authorization, and cannot complete, promote, query,
or control anything. A draft left active across an Android reboot is automatically closed as
`INTERRUPTED_BY_REBOOT`; elapsed-realtime values from different boots are never compared as one
monotonic clock. Read-only status and historical replay remain available because they do not query
or control the vehicle.

Vehicle-bus currentness is based on the local receipt time of the newest validated live
`RAW_CAN_FRAME`, not the gateway's cumulative received-frame counter. Retained log chunks do not
refresh it. When raw CAN traffic stops, the overview falls back to `HISTORICAL EVIDENCE ONLY` even
if gateway-health notifications remain fresh.

## Versioned test library

`AndroidDiscoveryTestLibrary` contains 15 versioned templates:

1. ignition cycle;
2. cold start;
3. RPM sweep;
4. accelerator sweep;
5. brake pulse;
6. steering sweep;
7. wheel rotation;
8. A/C on/off;
9. blower sweep;
10. HVAC temperature sweep;
11. four-wheel-drive transition;
12. suspension settle;
13. tire-pressure change;
14. electrical load; and
15. controlled road test.

Each template has a stable ID, semantic version, category, purpose, safety classification,
step-by-step procedure, and a finite marker vocabulary. Only `PARKED_PASSIVE` templates can start
on this head-unit build. Passenger-supervised driving and specialist-setup templates remain visible
as planned protocols but are explicitly locked because their separate safety workflows do not yet
exist.

This distinction is important: the library describes how evidence should be collected. It does not
assert that a proposed signal or decoder is correct.

## Android operational persistence

SQLCipher schema version 5 adds immutable vehicle lineage to the raw evidence layer and retains the
version-4 Discovery lifecycle:

### `logical_frames` and `can_observations`

Every newly ingested row stores `vehicle_scope_id`, `vehicle_profile_revision_id`, and `source_id`
at receipt time. The compound identity is also part of the uniqueness and read indexes. A physical
ESP32 source ID is not treated as a vehicle identity because the gateway can be unplugged and moved
to another vehicle. Discovery summaries, analysis, capture cursors, replay, reliability tests, and
portable export must supply all three values; there is no source-only fallback.

### `discovery_capture_sessions`

An append/finalize lifecycle for one active Android draft. The vehicle/profile/source scope, full
immutable test-template snapshot and SHA-256, boot identity, exact PARKED health-frame authority,
start clocks, and evidence cursors are immutable after insertion. Finalization can only
change `ACTIVE` to `COMPLETED` or `ABORTED` and must supply monotonic end clocks and nondecreasing
source-scoped evidence counts. Normal completion stores a second, current PARKED authorization.
A partial unique index prevents two active captures.

### `discovery_event_markers`

Append-only operator observations and independent measurements. A marker contains:

- unique marker and capture IDs;
- versioned template event type and human label;
- state, observation, or manual-measurement kind;
- original value text and unit when measured;
- wall-clock and Android elapsed-realtime clocks;
- observer and optional note; and
- the nearest retained gateway capture/session/sequence/monotonic anchor when available; and
- the exact fresh PARKED gateway-health frame that authorized the append.

Markers can only be appended to an active capture whose vehicle/profile/source identity still equals
the current vehicle binding. Measurements require a finite number in the UI
and retain the originally entered text and unit; the app does not silently convert or reinterpret
them.

### `android_capability_observations`

Deduplicated, vehicle/profile/source-scoped Android-internal observations of current transport
availability and retained evidence. Saving one requires and records exact fresh PARKED authority.
The record uses nullable current-CAN state so a missing/stale health report remains unknown. Its
authority text states that absence is not proof that the vehicle lacks a capability.

All tables live inside the existing Keystore-wrapped SQLCipher database. There is no unencrypted
fallback. Schema-v3 Discovery rows lacked scope, template snapshots, boot identity, and health-frame
authorization; migration preserves them verbatim in explicitly named `_v3_unscoped` quarantine
tables. Schema-v4 raw frames and CAN observations had only a physical `source_id`, so schema 4 -> 5
preserves those rows in `_v4_unscoped` tables and preserves their dependent Discovery rows in
`_v4_unbound_evidence` tables. None of those quarantined rows are returned by production Discovery
queries. No migration guesses which vehicle produced legacy bytes.

## Clock and evidence lineage

A Discovery event needs more than the time shown on the head unit. Android therefore records:

```text
observed_at                 wall clock for cross-device review
elapsed_realtime_nanos      monotonic Android ordering
gateway_monotonic_us        nearest gateway capture clock, when evidence exists
source_sequence             nearest gateway sequence, when evidence exists
source_id + CAN session     physical/capture lineage
health frame sequence      exact PARKED mutation authorization
boot_id                    Android elapsed-realtime clock domain
```

The nearest gateway record is an anchor, not a claim that the event occurred inside that CAN frame.
Final analysis can align the marker to surrounding raw observations without losing the original
operator time.

## Candidate and promotion authority

The Candidate Inbox reuses the existing SHA-pinned Toyota research pack, evaluator, and validation
mission planner. It exposes only facts already supported by retained evidence:

- hypothesis ID and target field;
- retained record/session counts;
- target evidence status;
- research priority; and
- the next validation mission.

`researchPriority` answers “what experiment is useful next?” It is never displayed or stored as
signal confidence. `confidence` remains null. Candidate source presence and target capture can be
checked from retained evidence; these facts do not establish a decoder.

Raw-CAN promotion remains closed until all checks exist:

- canonical signal definition;
- source and applicability;
- decoder revision;
- type and unit;
- plausible range;
- freshness rule;
- target-vehicle capture;
- independent corroboration; and
- golden replay.

There is no Promote action in this milestone. Owner gauges and the digital twin consume zero
research-only raw-CAN signals.

## Android-internal versus portable Discovery contracts

The new Kotlin records deliberately use an `Android...` prefix. They support a live operational
draft inside the head unit and are **not** JSON-compatible equivalents of the platform-neutral Swift
contracts in `4runner-vehicle-health-os/ios/Core/.../DiscoveryDomain.swift`.

| Android operational record | Portable record | Required mapping work |
| --- | --- | --- |
| `AndroidDiscoveryCaptureDraft` | `CaptureSession` | finalize archive, vehicle profile hash, manifest/archive SHA-256, complete raw provenance |
| `AndroidDiscoveryMarkerRecord` | `EventMarker` | canonical contract/version, portable ID, source enum, capture/gateway time mapping |
| `AndroidVehicleCapabilityObservation` | `VehicleCapabilitySnapshot` | canonical source set, gateway identity, protocol/ECU/PID map, portable provenance |
| `AndroidCandidateResearchItem` | `CandidateSignal` | canonical field definition, evidence references, scoring contract, validation state |
| `AndroidSignalPromotionGate` | promotion checklist | map only after shared checklist revision and evidence IDs are finalized |

Cross-platform serialization must happen at an explicit export assembler after a capture has a
verified vehicle profile and checksummed archive. Reusing the same unprefixed type names would hide
missing authority, so this implementation does not do that.

## Replay boundary

Replay is permitted with motion `UNKNOWN` because it is entirely local and read-only. It is blocked
while a live vehicle session is running so CPU/UI work cannot contend with BLE ingestion or be
mistaken for live evidence. It reads a bounded, exact vehicle/profile/source set of retained
observations and runs
them through:

```text
stored CAN observation
  -> deployed binary CAN payload
  -> VHOS envelope + CRC32C
  -> fragmented stream decoder
  -> CAN observation decoder
  -> order/payload identity oracle
```

The screen is always labeled `HISTORICAL REPLAY • NOT LIVE`. Normal replay and a ×20 load path are
available. Replayed observations do not become current vehicle readings and do not update health
models.

## Verification

Automated coverage includes:

- unique, versioned, safety-classified test templates;
- active/final capture lifecycle and nondecreasing evidence cursors;
- cross-boot interruption recovery and immutable historical template snapshots;
- measurement-value and marker-anchor validation;
- scoped nullable capability/availability evidence with exact PARKED lineage;
- immutable raw vehicle/profile/source bindings and cross-profile read isolation;
- marker and successful-completion rejection after a vehicle/profile scope transition;
- stale, moving, unknown, future, degraded, and fresh-PARKED safety cases;
- strict gateway-health motion decoding;
- candidate priority/confidence separation; and
- promotion remaining closed without the complete evidence checklist.

Run the repository gate with JDK 17:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew test lint assembleDebug
```

## Remaining acceptance gates

This source milestone still needs physical acceptance on the Android 13 head unit:

1. install the generated APK without replacing owner evidence;
2. validate SQLCipher schema 3 -> 4 and 4 -> 5 quarantine migrations on a copy of the field database;
3. prove the gateway publishes fresh `PARKED`, `MOVING`, and `UNKNOWN` health transitions;
4. confirm every mutating button locks within 2 seconds of stale/moving health;
5. complete and abort real parked-passive drafts, then inspect event/evidence alignment;
6. perform a process death and power-cycle while a draft is active;
7. replay a completed capture at 1× and ×20 and compare exact payload/order results; and
8. implement the checksummed portable export assembler before iPhone sync is claimed.
