# Offline link reliability lab

Status: implemented in Android source `0.1.0-dev.10`  
Contract: `transport.link-reliability-matrix@1.0.0`

## What the owner can run

After verified iPhone evidence has been imported, tap **Run reliability matrix**. The head unit
reads immutable CAN observations from its encrypted append-only store and runs 15 deterministic
communication scenarios without acquiring either ESP32 and without modifying live state.

The screen is always labeled:

```text
OFFLINE LINK RELIABILITY LAB • NOT LIVE
REAL_CAPTURE_REPLAY
```

Each row reports expected/observed communication quality, accepted versus expected unique records,
induced lost frames, duplicate evidence rejections, stale-epoch rejections, reconnects, decoder
recovery, and maximum decoder buffer. `DEGRADED` is the expected result for a deliberately damaged
scenario; it passes only when the damage is detected and all later expected evidence remains exact.

## Conditions covered

1. 20-cycle clean soak;
2. ATT MTU 23 / 20-byte payload fragments;
3. changing 20/61/97/185/244-byte chunk boundaries;
4. 64-frame application bursts;
5. deterministic 2.5-second short stalls;
6. repeated complete frames;
7. repeated notification fragments;
8. missing notification fragments;
9. payload corruption;
10. notification reordering;
11. connection loss in the middle of a logical frame;
12. callbacks from a prior physical-link epoch;
13. modeled 20-second supervision timeout and recovery;
14. bounded downstream queue overrun expressed as a complete outer-sequence gap; and
15. mixed loss, corruption, reordering, duplicate delivery, and reconnects.

## Non-negotiable budgets

- Clean paths tolerate no unexplained loss, mutation, reordering, or accepted duplicate identity.
- Evidence identity is `(source ID, capture session ID, source sequence)` and persists across BLE
  reconnects.
- A dead physical link clears incomplete decoder bytes; its quality counters remain.
- A callback from an old link epoch is rejected before decode or storage.
- A damaged logical frame may be rejected, but the next CRC-valid frame must recover exactly.
- Outer sequence regression is rejected and forward gaps remain explicit communication evidence.
- The deterministic decoder-buffer ceiling is 262,144 bytes; the pinned burst case uses 4,608.
- Communication degradation cannot create a vehicle-health conclusion.

## Recovery-loop correction

Android formerly reset its consecutive-failure counter when it merely saw another BLE
advertisement. A gateway that advertised normally but repeatedly failed GATT connection,
subscription, or handshake could therefore stay at recovery attempt 1 indefinitely.

Source `0.1.0-dev.10` removes that reset. Recovery history now clears only after a CRC-valid,
identity-validated VHOS handshake (or an explicit owner/radio restart). Repeated scan/platform/GATT
failures therefore advance through the bounded 5/15/30/60-second recovery policy and expose owner
reacquisition instead of becoming a hot loop.

## CAN discovery display

The Android discovery card now exposes the useful parts of the saved evidence without inventing
signal meaning:

- identifier-specific additive-checksum candidate counts and match rates;
- repeated raw byte positions, range, and maximum disagreement;
- raw big-endian word correlations, paired-sample counts, and median raw ratios; and
- an explicit `MEANING UNVERIFIED` label on every candidate.

Current corpus examples include eight 100% checksum candidates; `0x025` bytes 4/5/6 agreeing in
667 retained records; `0x2C4[0:16]` ↔ `0x2D0[0:16]` correlation 0.992130; and
`0x022[0:16]` ↔ `0x223[0:16]` correlation -0.999643. These remain experiment candidates, not RPM,
steering, throttle, brake, or vehicle-health values.

## Automated verification

The Kotlin matrix uses the checksum-pinned 256-record real-capture fixture. Its 40-cycle test
executes 13,824 wire deliveries across all scenarios and verifies exact survivors, deduplication,
epoch rejection, decoder recovery, explicit outer-sequence gaps, and bounded memory.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew test lint assembleDebug
```

GitHub Actions runs this command for every pull request and push to `main`.

## What remains physical

This lab is intentionally honest: a JVM cannot reproduce the head unit's Bluetooth controller,
vendor framework bugs, nearby 2.4 GHz interference, ESP32 task scheduling, supply brownouts,
NimBLE mbuf pressure, flash latency, or live TWAI interrupt load. The next hardware session must
still cover a 30-minute live soak, RSSI walk, Wi-Fi/Bluetooth coexistence, hard ESP32 power loss,
Android service restart, iPhone/Android ownership handoff, history-transfer congestion, and 20
saved-bond reconnect cycles with synchronized UART/mobile logs.
