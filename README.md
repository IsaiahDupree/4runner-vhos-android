# 4Runner VHOS Android head unit

## Install on the head unit

### [DOWNLOAD VEHICLE HEALTH OS FOR ANDROID (.APK)](https://github.com/IsaiahDupree/4runner-vhos-android/releases/download/android-v0.1.0-dev.4/app-debug.apk)

Current public development build: **0.1.0-dev.4** (`app-debug.apk`, 2.6 MB). The link above
downloads the installer directly and does not require a GitHub account.

While the vehicle is parked:

1. Tap **DOWNLOAD VEHICLE HEALTH OS FOR ANDROID (.APK)** above.
2. Approve the browser download, then open `app-debug.apk` from Downloads or the notification.
3. If Android asks, allow this browser to **Install unknown apps**.
4. Tap **Install**, then **Open**.

[Open the public Release Hub](https://isaiahdupree.github.io/4runner-vhos-release-hub/) ·
[View release details and checksum](https://github.com/IsaiahDupree/4runner-vhos-android/releases/tag/android-v0.1.0-dev.4)

Native Android client for the 4Runner Vehicle Health OS. It is deliberately separate from the
[OBD/CAN firmware](https://github.com/IsaiahDupree/4runner-vhos-firmware), the
[A/C telemetry node](https://github.com/IsaiahDupree/4runner-ac-telemetry-node), and the
[iPhone/product repository](https://github.com/IsaiahDupree/4runner-vehicle-health-os), while using
their versioned transport and evidence contracts.

The first vertical slice provides:

- service-filtered BLE discovery for VHOS gateways;
- independent OBD/CAN and A/C device state;
- exact 36-byte `VHOS` frame validation with header and payload CRC32C;
- encrypted GATT notification subscription before handshake negotiation;
- append-only local storage for validated raw logical frames and CAN observations, including
  transactional materialization of CRC-valid persistent capture records;
- real gateway, protocol, frame, error, and freshness indicators;
- recent evidence export and checksummed Android/iPhone handoff bundles;
- a connected-device foreground service for an explicitly started vehicle session; and
- a signed public Release Hub with APK hash, package, version, and signing-certificate verification
  before Android's owner-approved installer opens.

The current OBD gateway can exercise the complete BLE path. The current A/C ESP32-S3 recovery image
does not advertise BLE and will correctly remain `FIRMWARE NOT READY`; the app does not invent A/C
data while that firmware milestone is pending.

The deployed OBD firmware sends every framed response type over one encrypted multiplexed stream
characteristic. Android enables exactly that one CCCD before requesting the handshake; the separate
health and OTA characteristics remain registered for GATT compatibility but are not subscribed.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT=/Users/isaiahdupree/Library/Android/sdk
./gradlew test lint assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Repository relationships

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for ownership boundaries and
[docs/DEVICE-CONNECTION-CONTRACTS.md](docs/DEVICE-CONNECTION-CONTRACTS.md) for the exact devices and
services the app accepts. The one-trip vehicle test is in
[docs/HEAD-UNIT-COMMISSIONING.md](docs/HEAD-UNIT-COMMISSIONING.md).
