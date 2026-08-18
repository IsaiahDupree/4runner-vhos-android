# Head-unit commissioning and one-trip acceptance

## Save the hardware audit first

Record the exact manufacturer/model, Android version and API level, build fingerprint, ABI, display
resolution/density, free storage, BLE feature declaration, Bluetooth address behavior, power/sleep
behavior, and distribution method in the vehicle record. `minSdk 26` is a build baseline, not proof
that an unknown head unit is accepted.

## First install using the iPhone

Android software does not run on the iPhone. The iPhone is the owner-controlled download and
handoff console; Android retains authority over APK installation.

1. Enable the iPhone Personal Hotspot and join it from the head unit.
2. In the head-unit browser, open `https://isaiahdupree.github.io/4runner-vhos-release-hub/`.
3. Select the Android head-unit artifact and download the APK.
4. Android may require **Install unknown apps** permission for the browser or file manager that
   opened the APK. Grant it only to that owner-selected source, then approve the system installer.
5. Launch VHOS and confirm the package is `dev.vhos.headunit` and the displayed version matches the
   catalog. Future updates can be downloaded, hash/package/certificate verified, and sent to the
   owner-approved installer from the app's own Signed Release Hub.

The iPhone VHOS app can also verify and stage the APK under **Releases**, then expose the verified
file through the iOS share sheet. That is a file handoff, not a silent cross-device install. The
current build does not run an offline HTTP server on the iPhone; without internet, use an
owner-selected local file-transfer provider or USB storage and retain the catalog hash for
verification.

## Transfer captured evidence from iPhone

1. On iPhone, open **Evidence**, refresh/synchronize gateway logs, and choose **Prepare checksummed
   .vhossync bundle**.
2. Save or share `vhos-evidence-sync.vhossync` to a location visible from the head unit.
3. In Android VHOS, tap **Import** and select the bundle.
4. Confirm Android reports the verified record count and newly appended count. Re-importing the
   exact bundle is idempotent.
5. Confirm **LOCAL EVIDENCE** and **CAN observations** increase. Imported capture chunks are decoded
   only after the bundle manifest, segment SHA-256, envelope SHA-256, outer VHOS CRC32C, inner
   capture-record CRC32C, and listen-only proof all pass in one transaction.

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
