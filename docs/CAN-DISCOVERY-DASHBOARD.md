# CAN Discovery dashboard

Status: implemented in Android `0.1.0-dev.6`

## Purpose

The head unit can interpret saved CAN evidence without pretending that an unverified byte is a
vehicle signal. **Analyze saved CAN** reads the append-only `can_observations` table and renders a
versioned `DISCOVERY_CANDIDATE` report. It never mutates or replaces the raw observations.

The dashboard solves two different problems and keeps them visibly separate:

1. prove that acquisition is working; and
2. prioritize controlled signal-discovery experiments.

It does not diagnose the 4Runner and does not create a Vehicle Signal Pack.

## Evidence path

```text
ESP32 TWAI receive
  -> listen-only sampled capture record + record CRC32C
  -> complete VHOS capture-chunk frame + header/payload CRC32C
  -> iPhone append-only evidence / checksummed .vhossync export
  -> Android manifest, segment SHA-256, envelope CRC, record CRC, and source validation
  -> append-only SQLite can_observations row
  -> can.discovery.report@1.0.0
  -> CANDIDATES ONLY dashboard
```

Live Android capture follows the same raw persistence boundary. A device name, paired Bluetooth
entry, RSSI, or unvalidated notification cannot create a dashboard observation.

## Display tiers

### Proven acquisition

The following values resolve directly to persisted observation fields or source ordering:

- analyzed rows versus total stored rows;
- source and session counts;
- unique standard/extended identifier population;
- bitrate;
- listen-only record count;
- remote-request count;
- total source-monotonic duration;
- retained records per second;
- source-sequence span;
- sequence-derived observed-rate estimate; and
- retained sequence coverage.

Coverage is labeled **sampled coverage**, never packet loss. The deployed flight recorder retains
changed and unchanged frames at bounded per-ID rates. Missing source-sequence positions are
therefore expected unless gateway queue/drop/error counters say otherwise.

### Raw activity

For each identifier the app may display:

- retained records and sessions;
- DLC values;
- unique payload count;
- payload-change rate;
- raw dynamic byte positions; and
- the unscaled range of the first big-endian 16-bit word, explicitly named `raw BE16[0]`.

The activity list is ordered by dynamic-byte count, unique payload count, retained records, and
identifier. This is a work-prioritization ranking, not confidence in a signal meaning.

### Candidate integrity and relationships

The dashboard may report:

- matches under `toyota-additive-id-dlc-payload-v0` when at least five frames were checked and at
  least 95% match;
- identical dynamic byte columns within an identifier; and
- absolute Pearson correlation of at least 0.95 between raw `BE16[0]` fields paired within 250 ms
  in the same source session.

Every item remains `DISCOVERY_CANDIDATE`. A perfect checksum match is payload-integrity evidence;
it is not proof of identifier semantics. Correlation does not prove causation, field width, byte
order, scaling, or subsystem applicability.

### Interpretation lock

The production card explicitly blocks these labels until independent validation exists:

- RPM and idle quality;
- speed and wheel speed;
- gear and driveline slip;
- throttle/pedal position;
- steering, yaw, acceleration, and torque;
- brake pressure;
- temperature and pressure; and
- normal/abnormal thresholds or health conclusions.

Promotion requires exact source bytes, field transform, unit, range/cadence, target-vehicle
applicability, independent reference evidence, versioned decoder, and golden replay tests.

## Current saved-capture baseline

The companion engineering CLI replayed the five saved iPhone sessions available on 2026-08-18:

| Metric | Result |
| --- | ---: |
| Retained observations | 2,544 |
| Sessions | 5 |
| Unique identifiers | 17 |
| Bitrate | 500 kbit/s |
| Standard identifiers | 2,544/2,544 |
| Listen-only proof | 2,544/2,544 |
| Remote requests | 0 |
| Total sampled duration | 68.986 s |
| Estimated observed traffic | 542.342 frames/s |
| Retained rate | 36.877 records/s |
| Sampled sequence coverage | 6.799% |

Current candidate-only findings include:

- 2,048/2,048 additive-checksum matches across eight candidate ID families;
- `0x025` bytes 4/5/6 agreeing across 327 retained records; and
- raw `0x2C4 BE16[0]` / `0x2D0 BE16[0]` correlation 0.964242 across 323 bounded pairs.

Those numbers are useful for choosing the next experiment. They are not production decoders.

The authoritative machine-readable engineering report and source-file SHA-256 values live in the
[product repository](https://github.com/IsaiahDupree/4runner-vehicle-health-os/blob/agent/ios-hardware-foundation/docs/evidence/can-discovery-2026-08-18.report.json).

## Head-unit workflow

1. Transfer a checksummed iPhone `.vhossync` evidence bundle to the head unit.
2. Tap **Import** and select the bundle.
3. Android validates the manifest, segment hashes, logical envelopes, capture records, and
   listen-only flags before the transaction commits.
4. Tap **Analyze saved CAN**.
5. Read **PROVEN ACQUISITION** first. If source quality is degraded, stop interpretation.
6. Use **RAW ACTIVITY**, **INTEGRITY CANDIDATES**, and **RAW RELATIONSHIPS** to select one controlled
   experiment.
7. Export the original evidence—not a screenshot of the derived card—for desktop analysis.

Analysis runs off the UI thread and is safe while BLE is disconnected. The current bounded window
is the newest 100,000 rows. If the store is larger, the card prints `analyzed of total` so a partial
window cannot masquerade as the entire evidence set.

## Verification

The pure Kotlin analyzer is covered by golden records that exercise:

- acquisition counts and version/authority labels;
- additive-checksum candidate detection;
- a known 2:1 raw-word relationship;
- repeated dynamic byte channels; and
- rejection of any record lacking listen-only proof.

Full Android acceptance remains:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT=/Users/isaiahdupree/Library/Android/sdk
./gradlew test lint assembleDebug
```

Physical acceptance requires importing the latest iPhone bundle on the installed Q91-A4-CPL head
unit, confirming the expected five-session baseline, rotating the display, stopping/restarting the
app, and proving the raw database and analysis remain intact.

## Next development increment

Add immutable experiment markers and clock-mapping evidence, then rank byte/bit fields against
owner actions and an independently observed reference stream. The next useful car sequence is:

```text
ignition off -> accessory -> start -> settled idle
idle -> throttle blip x3
steering center -> left -> center -> right -> center
brake rest -> press/release x3
A/C off -> request -> observed cycle
park -> drive -> steady speed -> coast -> stop
```

The resulting hypotheses remain proposals until the Vehicle Signal Pack promotion gate passes.
