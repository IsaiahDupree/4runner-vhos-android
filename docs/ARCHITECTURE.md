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

Imported live-CAN frames and persistent capture-log chunks are materialized into the CAN-observation
table inside the same transaction. The outer VHOS frame CRC32C, portable-record envelope SHA-256,
capture-chunk shape, each stored record's inner CRC32C, and `listen_only=true` must all pass first.
The original logical envelope remains the authoritative evidence; materialization never replaces it.

`core:discovery` analyzes a bounded read-only snapshot of those materialized observations. Its
`can.discovery.report@1.0.0` output separates acquisition facts from raw statistical candidates and
does not persist a vehicle-signal meaning. The head-unit UI labels the complete section
`CANDIDATES ONLY`; RPM, speed, gear, steering, brake, temperature, pressure, thresholds, and health
conclusions remain unavailable until the shared Vehicle Signal Pack promotion process succeeds.

Neither app silently takes over BLE from the other. The head unit exposes **Release for iPhone**,
which closes GATT cleanly and records the release. Android can re-acquire only after explicit owner
action or a configured vehicle-session policy.

## Release distribution

Android and iPhone consume the detached-P-256-signed catalog from the separate public
`4runner-vhos-release-hub` repository. Android verifies the APK byte count, SHA-256, package ID,
version code, and signing-certificate SHA-256 before opening the operating system installer. The
app never silently enables unknown-source installation. ESP32 catalog entries are visible for
consistent fleet state, but mobile delivery cannot override firmware safety or recovery gates.
