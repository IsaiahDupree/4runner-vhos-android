# PARK authority and passive selector bootstrap

## Why the returned test still said `UNKNOWN`

The current gateway does not yet have an independently validated transmission-selector decoder.
Its health contract therefore reports `vehicle_motion: UNKNOWN`. That result is honest: a quiet bus,
zero road speed, a stationary phone, or an owner saying the selector is in Park does not prove that
the transmission is in Park.

Normal Discovery mutations, capability scans, OTA, and diagnostic controls continue to require a
fresh validated gateway-health frame whose deterministic motion authority is `PARKED`. This document
does not weaken that rule.

## The narrow bootstrap

The installed test library now contains one exact evidence-only procedure:

`discovery.transmission.selector-bootstrap@1.0.0`

It exists to collect the labeled passive evidence needed to discover a selector signal without first
pretending that signal already exists. The head unit enables this procedure only when all of these
facts are current at the same time:

| Required fact | Evidence source |
| --- | --- |
| Validated VHOS application contract is streaming | Current BLE/GATT session |
| Motion authority is exactly `UNKNOWN` | Newest validated gateway-health frame |
| Gateway and live CAN observation are listen-only | Handshake, health validation, and raw record |
| `capture.passive` is advertised | Current validated handshake |
| Recorder is active | Newest gateway-health frame |
| Health contains a deployed uint32 `capture_session_id` | Gateway-health payload |
| A live `RAW_CAN_FRAME` was received within five seconds | Current BLE receipt, never retained history |
| Health capture session equals raw-CAN capture session | Exact session lineage |
| Both health and raw records are at most five seconds old | Android receipt clocks |
| The raw anchor exists in the current encrypted vehicle/profile/source scope | SQLCipher evidence store |

Any missing, stale, future-dated, changed-session, changed-gateway, non-listen-only, inactive-capture,
moving, parked, or degraded state fails closed. `PARKED` is deliberately ineligible for this exception:
once deterministic Park authority exists, normal parked procedures are used instead.

## Vehicle procedure

1. Use level ground, chock the wheels, and apply the parking brake.
2. Keep the engine off, turn ignition on, and hold the foot brake.
3. Open **Discovery → Test Library → Park / Selector Bootstrap**.
4. Begin the immutable capture only when the evidence-only gate is ready.
5. Follow and mark the installed sequence: safety setup, Park, Reverse, Neutral, Drive, return to Park.
6. Repeat the sequence in an independent capture. Corroborate it with Toyota Techstream or another
   authoritative selector-state reference before promoting any decoded field.

The bootstrap does not accept arbitrary custom markers. The UI exposes only the next installed
marker, and the encrypted store independently rejects missing, duplicate, reordered, substituted,
or extra markers. Each accepted marker is append-only and is bound to the exact current raw-CAN
record, gateway identity, capture session, source sequence, and gateway monotonic clock present
when the button was pressed.

## Persistence and restart behavior

SQLCipher schema version 9 stores the full mutation authorization beside capture starts,
finalizations, and markers. The original health-frame lineage remains in first-class columns; the
v6 evidence-only fields remain stored in a versioned extension, v7 excludes imported/recovered
rows, v8 requires durable RAW-CAN parent/origin lineage for live anchors, and v9 persists marker
boot identity and strict chronology:

- mutation authority and health motion value;
- gateway capture session;
- exact live raw-CAN anchor;
- raw-CAN receipt time;
- listen-only and capture-active proof; and
- the required `capture.passive` capability.

Capture and marker rows are inserted, never updated into a different meaning. A marker cannot cross
gateway capture sessions. A successful evidence-only completion must still have fresh matching
UNKNOWN/passive authority and a retained final raw-CAN anchor that strictly follows the last
installed marker's raw-CAN anchor. A marker and finalization can never reuse one bus observation.
If the link, authority, session, or
vehicle profile changes, only a safety abort is available. A reboot also closes an unfinished draft
as interrupted; it never upgrades it to a completed capture.

Freshness is checked twice: the Activity reads the newest synchronous connection state immediately
before each operation, and the encrypted append method independently rejects health or raw-CAN
receipt authority older than five seconds or dated in the future. Instrumentation-only entry points
accept an explicit clock solely so those boundary checks remain deterministic in tests.

## What this can and cannot unlock

The resulting capture is research evidence. It may be replayed, compared, and analyzed to rank raw
CAN fields that correlate with P/R/N/D labels. It cannot by itself:

- declare the vehicle parked;
- unlock OTA or active/read-only diagnostic execution;
- save a vehicle capability snapshot;
- promote a candidate into the production Signal Registry;
- populate an owner-facing gear indicator; or
- establish that a candidate applies to another Toyota model or model year.

Production Park authority requires a versioned decoder, target-vehicle repeatability, independent
corroboration, freshness and plausibility rules, and golden replay. Until those gates pass, the
gateway must continue to report `UNKNOWN`.

## Automated coverage

The JVM suite verifies the exact-template identity, all freshness and lineage failures, capture
session parsing and uint32 bounds, strict separation from PARKED authority, immutable draft and
marker validation, the existing deterministic PARKED path, and the transport reducer rule that only
live `RAW_CAN_FRAME` traffic may refresh live lineage while retained chunks cannot. Android
instrumentation coverage verifies SQLCipher round-trip persistence, stale/future mutation rejection,
exact raw-anchor existence, capture-session binding, and append-only marker identity. Hardware
acceptance still requires repeating the procedure on the installed Android head unit and gateway;
desktop tests do not prove a vendor BLE stack or vehicle signal meaning.

### Migration acceptance still required on Android

The v5-to-v6 migration adds only nullable authorization-extension columns, so legacy PARKED rows
retain their first-class health lineage and decode with PARKED defaults. Schema 6 -> 7 adds import
provenance and deliberately defaults all pre-v7 raw/CAN rows to non-authoritative because their
origin cannot be reconstructed. It backfills only rows on a real validated source in a scope with
no legacy import receipt and with no contradiction between the source registry and stored frame
roles; imported, receipt-bearing, or role-conflicted scopes stay false. The current Mac has no
attached Android target, so these encrypted
migrations have not yet executed through SQLCipher's device runtime. Before release promotion, run
v5, v6, v7, and v8 fixtures on the target head unit (or an API-26+ emulator), prove existing captures remain
unchanged, prove definite local rows retain authority, and prove imported/ambiguous pre-v7 rows
cannot satisfy live-anchor checks. Also prove that pre-v8 rows cannot invent RAW-CAN parent lineage
and pre-v9 marker rows cannot authorize completion without a durable boot identity. Destructive
migration is forbidden.
