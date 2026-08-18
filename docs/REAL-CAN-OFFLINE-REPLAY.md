# Real CAN offline replay

Status: implemented in Android source `0.1.0-dev.10`

## Purpose

The head-unit app can now exercise its real transport and CAN-observation pipeline without either
ESP32 or the vehicle. It replays the append-only observations already imported from the iPhone and
keeps historical evidence visibly separate from live state.

The replay surface is for development, regression testing, and signal-discovery review. It cannot
write CAN data, append duplicate evidence, update the digital twin, or create a health conclusion.

## Truth boundary

Every replay screen must show both:

```text
HISTORICAL REPLAY • NOT LIVE
REAL_CAPTURE_REPLAY
```

The source session ID, source sequence, arbitration identifier, raw payload, original source time,
progress, and decoder recovery counters remain visible. The app also displays the interpretation
lock: bytes do not become RPM, speed, gear, throttle, steering, brake, temperature, pressure, or a
health statement until an accepted Vehicle Signal Pack proves the mapping.

## Production path under test

```text
encrypted append-only CAN observations
  -> deterministic source/session ordering
  -> deployed 36-byte observation encoder
  -> deployed CRC32C VHOS envelope encoder
  -> source-time or full-speed fragment scheduler
  -> production self-resynchronizing GatewayFrameStreamDecoder
  -> production CanObservation decoder
  -> exact source identity/order/payload comparison
  -> read-only replay UI and diagnostics
```

The decoder exposes discarded bytes, recovery events, rejected frame candidates, and buffered
bytes. Those values diagnose the communication path; they do not diagnose the vehicle.

## Owner controls

- **Replay saved CAN** uses the original source-monotonic intervals multiplied by the configured
  speed factor. The current UI uses 25× source time so motion remains visible without a long wait.
- **Stress replay ×20** runs the complete saved observation set twenty times at full speed.
- **Stop replay** cancels at a record boundary and reports the bounded partial result.

Starting a new replay cancels the old one. App destruction also cancels replay work. No replay
result is inserted into the evidence database.

## Automated gates

The Kotlin suites use a 256-record fixture taken directly from the checked-in product corpus. Its
SHA-256 is
`af2305021c2d48d89c55d1739da407d78ee28baa39cce63125d0656672f58aed`.

Tests require:

- 8,192 synthetic-contract envelopes under hostile transport fragmentation;
- 5,120 real observations under hostile fragmentation with exact order and bytes;
- 5,120 persisted-observation replays with exact identity and no duplicates;
- recovery after a removed notification fragment;
- recovery after a corrupted payload;
- recovery after a mid-frame disconnect/reset;
- bounded replay cancellation;
- real-corpus discovery facts, checksum candidates, repeated channels, and raw relationships; and
- complete Android unit tests, lint, and debug APK assembly.

The separate [offline link reliability lab](LINK-RELIABILITY-LAB.md) extends these frame-level
checks into a 15-condition matrix with MTU churn, bursts, duplicates, stale physical-link epochs,
modeled supervision timeout, queue overrun, mixed interference, and a 40-cycle fixture soak.

Run everything with:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew test lint assembleDebug
```

GitHub Actions runs the same command for every pull request and push to `main`.

## Current real-data baseline

The canonical product corpus contains eight sessions, 5,176 retained observations, 17 identifiers,
500 kbit/s standard 11-bit traffic, and listen-only proof on every record. It covers 140.831 seconds
of sampled source time. Sequence/time estimates approximately 538.880 observed bus frames per
second; the flight recorder retained approximately 36.753 records per second, or 6.8196% sequence
coverage by policy.

The current raw candidates are useful for experiment design but are not accepted signal labels:

- eight identifier families match the candidate additive checksum in every applicable record;
- `0x025` bytes 4/5/6 agree in 667/667 retained records;
- `0x2C4[0:16]` and `0x2D0[0:16]` correlate at 0.992130 across 625 bounded pairs; and
- `0x022[0:16]` and `0x223[0:16]` correlate at -0.999643 across 142 bounded pairs.

The authoritative corpus and full report remain in the product repository so Android does not fork
vehicle evidence or signal authority. Android carries only a checksum-pinned test fixture and reads
owner-imported evidence at runtime.

## What still requires hardware

Offline replay covers framing, fragmentation, corruption, buffering, database reads, analysis,
UI updates, cancellation, and sustained software load. It cannot prove RF coexistence, controller
behavior, Android vendor Bluetooth bugs, ESP32 power integrity, TWAI interrupt pressure, flash
latency, or in-vehicle electrical behavior. A build should pass this suite before any physical
bench run; only then should it consume a car trip.
