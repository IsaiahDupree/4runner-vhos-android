# Device connection contracts

This document defines what the Android head unit connects to and the evidence required before the
app calls a device healthy. Names, RSSI, and an operating-system pairing entry are discovery hints;
they are never proof of device identity or vehicle compatibility.

## OBD/CAN gateway: implemented

The current development target is the MrDIY ESP32 CAN Shield gateway running the public VHOS OBD
firmware. The observed development unit formerly advertised as `VHOS-MRDIY-B08D14`; Android now
normalizes it to canonical name `VHOS-4R-OBD-B08D14`, but accepts it by protocol contract rather
than by that owner-facing label.

| Item | Required value or behavior |
| --- | --- |
| BLE service | `33613EB3-FFCA-42D1-83FA-A18F12B3F123` |
| Command characteristic | `B3D3279B-0244-4D54-A2AB-A1AB47A5FC0A` |
| Multiplexed stream notification | `265B90C0-A600-4659-BBBD-5CDA411C49CC`; the only CCCD Android enables |
| Registered compatibility characteristic | `BCB5699A-A9B4-49B8-B69B-D2DFF19B41A9`; health frames arrive on the multiplexed stream |
| Registered compatibility characteristic | `18D21F8E-D190-4DB3-923C-27BBFC355874`; OTA frames arrive on the multiplexed stream |
| Envelope | 36-byte little-endian `VHOS` header plus bounded payload |
| Integrity | header CRC32C and payload CRC32C must pass |
| Identity | `gateway.handshake` contract version `1.0.0` |
| Required capabilities | `capture.passive`, `evidence.export` |
| Safety proof | handshake and live health both report `listen_only=true` |
| Readiness proof | validated identity, the current-link stream subscription, fresh health, persisted evidence |

Android performs service-filtered scanning, connects with BLE transport, discovers the complete
service, validates that the compatibility characteristics remain registered, enables exactly one
encrypted multiplexed stream CCCD, and only then sends the handshake request. Evidence, health,
capture-log, and OTA frames remain independently typed and CRC-protected inside that stream. This
matches the physically accepted iPhone/firmware baseline and avoids the multi-CCCD pairing failure
that previously exhausted the ESP32 BLE host. GATT authentication failures trigger a bounded
Android pairing/retry flow. Users should not have to delete the device from Bluetooth settings
during normal reconnects.

Canonical owner-facing names are `VHOS-4R-OBD-<MAC suffix>` and
`VHOS-4R-AC-<MAC suffix>`. Android BLE addresses remain internal transport metadata; the complete
handshake `gateway_id` is the immutable evidence identity.

## A/C telemetry gateway: firmware contract pending

The A/C ESP32-S3 is a separate physical and source identity. Its current recovery firmware does not
advertise the VHOS BLE service, so Android deliberately reports `FIRMWARE NOT READY` and stores no
invented sensor readings.

Before Android can accept it, the A/C repository must publish golden frames for a versioned sensor
handshake and telemetry messages containing, at minimum:

- stable sensor-node ID, hardware revision, firmware build ID, and protocol version;
- POST state, supply state, ADC configuration and calibration identity;
- raw ADC observations with channel identity and capture monotonic timestamps;
- pressure/temperature derivations with calibration and equation-version lineage;
- sample/drop/error counters and storage state; and
- signed OTA, probationary boot, and rollback status.

The A/C message codes must be newly allocated in the shared wire registry. They must not reuse the
currently deployed OBD experiment and OTA codes 8 and 9.

## iPhone synchronization: implemented without simultaneous BLE ownership

Android and iPhone do not silently compete for the ESP32 GATT connection. **Release for iPhone**
closes the Android connections while preserving the visible released state. The phone can then own
the live gateway connection. Android only reacquires after an explicit owner action or a future,
documented session policy.

For durable synchronization, either app can create a `.vhossync` bundle. The stored ZIP contains a
manifest and NDJSON segments. Import verifies safe paths, ZIP CRC, segment SHA-256, record counts,
complete VHOS envelope CRC32C, source metadata, and per-envelope SHA-256 before append-only storage.
Bundle ID plus manifest hash makes repeated import idempotent. Validated live-CAN and capture-log
chunk envelopes are additionally decoded into Android's append-only CAN-observation table; every
stored capture record must pass its inner CRC32C and retain `listen_only=true` before the import
transaction commits.

The importer rejects duplicate JSON names recursively and requires the exact manifest, creator,
segment, recovery, and portable-record key sets. It applies the interoperable per-bundle profile to
v1 and v2 equally: 33 ZIP entries, 32 data segments, 20,000 records, 16 MiB per data segment, a
1 MiB manifest, 17 MiB aggregate uncompressed entries including the manifest, and an 18 MiB total
ZIP archive. The raw archive is copied to a bounded temporary file; segment bytes are then hashed
and strict-UTF-8 records are parsed incrementally, so a legal-size archive is never duplicated into
an archive-sized byte array, string, and line list. Export uses a bounded temporary NDJSON spool and
streams it through a total-archive limiter; the writer reserves manifest space and checks the exact
combined entry size. Evidence that exceeds any per-bundle limit must be emitted as multiple
independent bundles, each with its own bundle ID, manifest, segment digests, and import receipt.

Recovered portable evidence uses manifest version `2.0.0`. Its manifest bytes must contain a
`recovery` object with classification `RECOVERED_PORTABLE_EVIDENCE`,
`vehicle_claims_authorized=false`, and a lowercase `source_ledger_sha256`. That digest must exactly
match the verified SHA-256 of the archive's only canonical logical-frame segment. Android preserves
v1 import compatibility, rejects undeclared manifest/creator/segment/recovery fields, exposes
validated recovery metadata for v2, and always labels imported bundles as non-live authority.
Recovery evidence can extend append-only history; it cannot validate
a BLE peer, select a current source, or authorize current vehicle-health claims.

Schema v9 persists the import receipt and raw/CAN-row provenance, including the recovery
classification, source-ledger digest, and `vehicle_claims_authorized=false`. Live-anchor lookup,
anchor containment, capture/test counters, and imported-only source acquisition all reject those
rows. The ordinary v1 exporter excludes recovery-classified rows, preventing a recovered v2 ledger
from leaving Android as unclassified v1 evidence; normal v1-to-v1 portable synchronization remains
available. The exporter also requires each stored frame role to equal its registered source role.
Exact duplicate physical identities are idempotent; a duplicate logical or CAN identity carrying a
different envelope/payload aborts the transaction and produces no receipt. Imported and recovered
provenance remains visible in persisted observations, summaries, analysis, and replay, and neither
class contributes to the current live capability gate.
CAN materialization also retains its parent logical-frame ID and origin message type. Only a local,
authorized `RAW_CAN_FRAME` with a durable parent can satisfy a current bootstrap anchor;
`CAPTURE_LOG_CHUNK` rows remain historical even when downloaded during a live session.

## Android head-unit baseline

The software build is pinned to JDK 17, Android Gradle Plugin 9.3.1, Gradle 9.5.0, compile/target SDK
37, and minimum SDK 26. Minimum SDK is only a compatibility hypothesis until the exact head-unit
manufacturer, model, Android release, ABI, BLE controller, sleep behavior, and installation method
are recorded during commissioning.

The app needs Android Nearby Devices/Bluetooth runtime permission and notification permission. Its
owner-started vehicle session runs as a `connectedDevice` foreground service so the operating
system keeps the BLE evidence session visible rather than hiding it as background work.
