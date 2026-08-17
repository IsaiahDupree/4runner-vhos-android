# VHOS Android repository instructions

This repository is the Android head-unit peer in the 4Runner Vehicle Health OS project.

## Safety invariants

- Treat every ESP32 as untrusted until the GATT service, complete CRC32C frame, handshake identity,
  protocol major, device role, and required capabilities are validated.
- CAN is listen-only by default. Do not add arbitrary CAN transmission, active tests, or generic
  diagnostic writes.
- Persist raw evidence before decoding it into UI state. Never replace raw evidence with a derived
  value.
- Keep OBD/CAN and A/C sessions, clocks, sequence counters, health, and failure states independent.
- Never show simulated vehicle or sensor values in production UI.
- Wi-Fi is off during normal operation. A temporary ESP32 network is allowed only through an
  authenticated, owner-approved, time-bounded BLE-negotiated operation.
- Cross-platform imports are append-only and must verify every declared SHA-256 before persistence.

## Verification

Use JDK 17 and Android SDK 37:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT=/Users/isaiahdupree/Library/Android/sdk
./gradlew test lint assembleDebug
```

Physical BLE and two-concurrent-GATT acceptance tests are required on the installed head unit; a
desktop JVM test is not evidence that a specific radio or vendor Android build works in the car.
