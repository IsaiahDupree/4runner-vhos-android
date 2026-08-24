# Android head-unit architecture

## Repository ownership

| Repository | Owns | Must not own |
| --- | --- | --- |
| `4runner-vhos-firmware` | OBD/CAN ESP32 firmware, passive capture, log storage, signed OTA | Android or iOS UI |
| `4runner-ac-telemetry-node` | A/C ESP32-S3 firmware, ADC and physical sensor acquisition | CAN decoding or head-unit state |
| `4runner-vhos-android` | Android BLE clients, local truth store, vehicle UI, import/export | ESP32 board code or iOS UI |
| `4runner-vehicle-health-os` | Product contracts, iPhone app, product/engineering specifications | Android platform implementation |

The repositories coordinate through versioned contracts and captured golden frames, not copied
business logic. Firmware remains independently flashable and recoverable when either mobile app is
absent.

## Runtime topology

```text
OBD/CAN ESP32  -- encrypted BLE --\
                                      Android head unit -- append-only SQLite
A/C ESP32-S3   -- encrypted BLE --/             |       -- VHOS sync bundle
                                                |
                                             owner release
                                                |
                                              iPhone
```

`DualGatewayManager` discovers the public VHOS service once and owns a connection object per
physical device. Each connection enables the one encrypted stream CCCD used by the deployed
firmware; evidence, health, capture, and OTA remain separate framed message types within that
stream. Each connection reassembles transport chunks independently. A complete frame must pass
magic, protocol-major, size, header CRC32C, and payload CRC32C checks before it reaches the database
or UI.

An OBD session is accepted only when a `gateway.handshake` identifies the physical gateway and
asserts listen-only operation plus passive-capture capabilities. A future A/C session will require
the sensor-node handshake and telemetry/POST capabilities. A device name is never identity proof.
Owner-facing labels follow the stable `VHOS-4R-OBD-<suffix>` and `VHOS-4R-AC-<suffix>` contract;
randomized BLE addresses are never presented as physical-device identity.

## Current wire baseline

The deployed firmware and iPhone app establish the current interoperable baseline:

- 36-byte little-endian `VHOS` envelope;
- message 1 handshake: JSON;
- message 2 live CAN record: 36-byte binary payload;
- message 4 gateway health: JSON;
- message 8 OTA control/status: JSON;
- message 11 capture-log request: 8-byte binary payload;
- message 12 capture-log index: JSON; and
- message 13 capture-log chunk: binary header plus CRC-protected 36-byte records.

This is intentionally more precise than the early all-protobuf design note. The production wire
encoding cannot be changed merely to make a client convenient. A future protobuf migration needs a
new protocol version, golden vectors in every repository, and a coordinated rollout.

## Evidence and iPhone sync

Android stores the complete validated logical envelope alongside source identity, source sequence,
source monotonic time, Android ingestion time, message type, and SHA-256. Decoders create additional
rows; they do not mutate the envelope.

The portable bundle is a ZIP archive containing `manifest.json` and one or more NDJSON segments.
The manifest declares the creator platform/app version, bundle ID, creation time, each segment's
media type, byte count, record count, and SHA-256. Import verifies safe relative paths, exact byte
counts, exact hashes, and exact record counts before an append-only transaction. An import receipt
keyed by bundle ID and manifest SHA-256 makes replay idempotent.

JSON parsing is strict before Gson model conversion. Duplicate names are rejected recursively in
the manifest and in every portable NDJSON record. Each record must contain exactly the canonical
portable-frame keys (`contract`, `contract_version`, source identity/clock fields, protocol and
message metadata, ingestion time, and envelope SHA-256/base64); undeclared aliases and missing keys
fail closed instead of being ignored by Gson. Scalar checks also match the shared JSON Schema:
creator strings and source IDs obey their length bounds, source roles are enumerated, source clocks
are canonical unsigned decimals, protocol/message/flag integers stay in byte ranges, timestamps
parse as instants, and SHA-256 values are exactly 64 lowercase hexadecimal characters.

Import resource limits use the interoperable per-bundle wire profile: at most 33 ZIP entries (one
manifest plus at most 32 segments), 20,000 records, 16 MiB per data segment, a 1 MiB manifest,
17 MiB of aggregate uncompressed entry bytes including `manifest.json`, and 18 MiB for the complete
ZIP archive. ZIP input is first copied to a bounded temporary file, then segments are hashed,
counted, strict-UTF-8 decoded, and parsed one bounded NDJSON line at a time; the importer never
creates an archive-sized byte array, archive-sized UTF-16 string, or full list of line strings. A
line is capped before JSON materialization, and the record ceiling is enforced as records arrive.
Manifest validation then matches exact entry names, byte counts, hashes, and record counts. The
exporter likewise spools and streams bounded NDJSON, reserves manifest space, checks the exact
aggregate size, and bounds the emitted archive. Evidence larger than this profile is split into
independent bundles with independent bundle IDs, manifests, hashes, and import receipts. Existing
v1 bundles remain compatible only when they fit these documented bounds.

The importer accepts manifest contract versions `1.0.0` and `2.0.0`. Version 1 remains unchanged.
Version 2 is reserved for recovered portable evidence and requires this additional manifest member:

```json
"recovery": {
  "classification": "RECOVERED_PORTABLE_EVIDENCE",
  "vehicle_claims_authorized": false,
  "source_ledger_sha256": "<64 lowercase hexadecimal characters>"
}
```

A v2 archive must declare exactly one `segments/logical-frames.ndjson` segment, and
`source_ledger_sha256` must equal that segment's declared SHA-256 exactly. The importer then verifies
the digest against the actual segment bytes. The SHA-256 exposed as `manifestSha256` covers the exact
`manifest.json` bytes, so it also binds the recovery classification, false authority declaration,
and verified ledger digest. Missing fields, alternate classifications, a true/non-Boolean authority
value, non-canonical hashes, extra ledger segments, a ledger-digest mismatch, or any unknown
top-level, creator, segment, or recovery field fail closed. The exact key sets prevent
authority-looking aliases from being interpreted differently by another implementation.

`ImportedEvidenceBundle.recoveryMetadata` is null for v1 and contains the validated v2 declaration
for recovery-aware storage and UI. `ImportedEvidenceBundle.isLiveAuthority` is importer-controlled
and always false for both versions; no manifest value can elevate imported history into a current
BLE source, current vehicle state, or authorization for vehicle claims.

SQLCipher schema v9 carries that boundary into storage. Every import receipt and every raw logical
frame/CAN row inserted by an import retains its bundle ID, manifest hash, contract version, recovery
classification, source-ledger hash, and `vehicle_claims_authorized=false`. Locally acquired rows are
written with no import lineage and `vehicle_claims_authorized=true`. The v6-to-v7 migration marks
legacy rows authoritative only when their source has a real validated Bluetooth address and their
vehicle/profile has no legacy import receipt. An `IMPORTED` source or any receipt makes the whole
legacy vehicle/profile scope ambiguous, so its rows remain non-authoritative and v1-export
ineligible. A source whose legacy logical rows conflict with its registered physical role is also
ambiguous: neither its logical rows nor its derived CAN rows are backfilled as live authority. The
upgrade callback asserts SQLiteOpenHelper transaction ownership before changing the schema or
creating the matching partial indexes.

Latest/contains anchor queries and live capture counters require authorized, non-imported rows, so
recovered history cannot satisfy selector bootstrap or other capture/test lineage. Ordinary v1
sync remains transitive for v1 history, but its export query excludes every recovery-classified row;
there is no implicit v2-to-v1 downgrade path. Export additionally requires the immutable source role
on each logical frame to match the registered source role. A source ID cannot change roles during
live validation or import. Historical analysis, replay, offline link-reliability results, summaries,
and UI retain explicit
`LOCAL_AUTHORIZED`, `IMPORTED_V1_HISTORY`, `RECOVERED_V2_HISTORY`, or
`AMBIGUOUS_LEGACY_HISTORY` provenance; only the first class contributes to live capability counts.

Evidence identities are also fail-closed. Re-importing the exact same logical envelope or exact same
CAN source/session/sequence payload is idempotent. Reusing either physical identity with different
metadata, envelope, or CAN payload is a contradiction: the entire SQLCipher transaction rolls back
and no import receipt is written. This prevents first-writer `CONFLICT_IGNORE` behavior from silently
discarding a conflicting observation while claiming that its archive was received.

Every new CAN row also records its origin message type and parent logical-frame row. Only a locally
authorized `RAW_CAN_FRAME` with a durable parent is eligible for latest/contains bootstrap-anchor
queries. A `CAPTURE_LOG_CHUNK` remains useful history but cannot masquerade as a current receipt.

Imported frames originally captured from live CAN, plus persistent capture-log chunks, are
materialized as historical rows in the CAN-observation table inside the same transaction. The outer
VHOS frame CRC32C, portable-record envelope SHA-256,
capture-chunk shape, each stored record's inner CRC32C, and `listen_only=true` must all pass first.
The original logical envelope remains the authoritative evidence; materialization never replaces it.

`core:discovery` analyzes a bounded read-only snapshot of those materialized observations. Its
`can.discovery.report@1.0.0` output separates acquisition facts from raw statistical candidates and
does not persist a vehicle-signal meaning. The head-unit UI labels the complete section
`CANDIDATES ONLY`; RPM, speed, gear, steering, brake, temperature, pressure, thresholds, and health
conclusions remain unavailable until the shared Vehicle Signal Pack promotion process succeeds.

The same module owns device-free historical replay. It deterministically rebuilds the deployed
CAN payload and CRC32C VHOS envelope from persisted observations, applies source-time or full-speed
fragment scheduling, and sends the bytes through the production stream and CAN decoders. Exact
source identity, order, and payload are the oracle. Fault profiles remove a notification fragment,
corrupt a payload, or reset the decoder mid-frame; later valid observations must recover with
explicit transport diagnostics. Replay is read-only and must display
`HISTORICAL REPLAY • NOT LIVE`; it cannot update the vehicle model.

Neither app silently takes over BLE from the other. The head unit exposes **Release for iPhone**,
which closes GATT cleanly and records the release. Android can re-acquire only after explicit owner
action or a configured vehicle-session policy.

## Release distribution

Android and iPhone consume the detached-P-256-signed catalog from the separate public
`4runner-vhos-release-hub` repository. Android verifies the APK byte count, SHA-256, package ID,
version code, and signing-certificate SHA-256 before opening the operating system installer. The
app never silently enables unknown-source installation. ESP32 catalog entries are visible for
consistent fleet state, but mobile delivery cannot override firmware safety or recovery gates.
