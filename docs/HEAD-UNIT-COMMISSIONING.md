# Head-unit commissioning and one-trip acceptance

## Save the hardware audit first

Record the exact manufacturer/model, Android version and API level, build fingerprint, ABI, display
resolution/density, free storage, BLE feature declaration, Bluetooth address behavior, power/sleep
behavior, and distribution method in the vehicle record. `minSdk 26` is a build baseline, not proof
that an unknown head unit is accepted.

## Bench test

1. Install the debug APK with `adb install -r app-debug.apk`.
2. Grant nearby-device and notification permissions in the app.
3. Power only the OBD gateway on a bench supply; do not connect it to a vehicle bus for pairing.
4. Start the vehicle session and confirm the app progresses through service discovery, encrypted
   subscriptions, handshake, and `CAN READY`.
5. Confirm the A/C card remains `FIRMWARE NOT READY` while its recovery image has no BLE service.
6. Stop/start the app twice and verify the validated gateway reconnects without forgetting it in
   Android settings.
7. Use **Release for iPhone**, connect with iPhone, then disconnect iPhone and explicitly reacquire
   from Android.

## Single vehicle trip

1. With the vehicle parked, connect the gateway to DLC3 and start Android capture.
2. Confirm `listen-only = true`, zero bus-off events, the detected bitrate, increasing received
   frames, increasing persisted observations, and recent health freshness.
3. Add markers for ignition-on, idle, throttle, brake, steering, A/C on/off, drive, coast, and stop.
4. End the session and export a VHOS sync bundle before unplugging the gateway.
5. Import the bundle on iPhone/desktop and verify the manifest, hashes, record counts, sequence
   range, and timestamps.
6. Export the full diagnostic report so a failed iteration contains enough evidence to fix from the
   bench without another immediate vehicle trip.

Never treat a green BLE link as proof of an OBD protocol. Vehicle-network readiness requires a
validated handshake, listen-only health, passive lock or an explicitly bounded read result, and
persisted evidence.
