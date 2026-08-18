# Android 13 / API 33 BLE recovery contract

## Fixed hardware boundary

This recovery design keeps the installed Android 13 (API 33) head unit, OBD/CAN ESP32, A/C ESP32,
vehicle wiring, and VHOS wire/GATT contracts unchanged. The field failure being addressed is Android
BLE scan error `3`, `SCAN_FAILED_INTERNAL_ERROR`, on the installed head unit. That code is evidence
that Android failed to start its local scan; it is not evidence of an OBD protocol failure.

## Single-owner rule

Only one client owns an ESP32 GATT session at a time.

- **Connect / Reacquire** assigns ownership to Android.
- **Release for iPhone** closes Android scans and GATT clients before assigning ownership to iPhone.
- Android never silently reacquires after an explicit iPhone release.
- The iPhone must disconnect and disable its automatic reconnect before Android reacquires.

The UI reports `OWNER ANDROID`, `OWNER IPHONE`, or `OWNER NONE`. A physical BLE link is never treated
as a validated VHOS or OBD connection.

## Acquisition state machine

1. An owner explicitly starts an Android vehicle session.
2. Android first attempts the most recently CRC-handshake-validated Bluetooth addresses from its
   append-only evidence database. An approved bonded VHOS device is a secondary direct candidate.
3. Every direct candidate must still pass service UUID, characteristic, encryption, complete frame
   CRC32C, handshake identity, role, protocol-major, and required-capability validation.
4. If no saved candidate is available or a direct connection times out, Android runs one balanced,
   service-filtered 12-second scan window.
5. A scan with no result backs off for 5, 15, 30, and 60 seconds.
6. Android stack failures such as code 3 use colder delays of 15, 30, 60, and 120 seconds.
7. After four bounded failures, automatic recovery pauses. The owner must tap **Connect / Reacquire**
   or use **Bluetooth settings** to cycle the head-unit radio.

Seeing another advertisement is not recovery. The consecutive-failure counter resets only after a
complete CRC-valid, identity-validated VHOS handshake, or after an explicit owner/radio restart.
Repeated GATT, subscription, and handshake failures therefore advance through 5/15/30/60 seconds
and open the circuit after the fourth automatic attempt instead of looping forever at attempt one.

This replaces the original unbounded two-second scan loop. Scan error names, numeric codes, recovery
attempts, and scheduled retry times remain visible in the OBD/CAN status card.

## Serialized GATT startup

The vendor Android stack receives one GATT operation at a time:

1. connect, with a 15-second deadline;
2. request MTU, with a three-second fallback;
3. discover the exact VHOS service, with a 12-second deadline;
4. enable the one encrypted multiplexed CCCD, with the existing 30-second pairing deadline;
5. reliably write the versioned handshake; and
6. accept the session only after a complete CRC-valid handshake response.

Connection, discovery, subscription, and handshake timeouts close the exact GATT client before a
bounded recovery is scheduled. A saved address is not trusted if its returned source ID or role
differs from the previously validated identity.

## Field acceptance

Run these while parked and preserve screenshots plus exported evidence:

1. **Cold Android ownership:** iPhone disconnected; Bluetooth on; Connect / Reacquire reaches a
   validated VHOS stream without code 3.
2. **Saved reconnect:** Stop, then Connect / Reacquire. The UI must report direct saved-gateway
   connection without scanning.
3. **iPhone handoff:** Release for iPhone, connect the iPhone, verify streaming, disconnect the
   iPhone, then explicitly reacquire from Android.
4. **Radio loss:** while Android owns the session, turn Bluetooth off for 15 seconds and back on.
   Android must wait while off, then resume through the bounded state machine.
5. **Gateway power loss:** remove ESP32 power for 30 seconds and restore it. Android must reconnect
   without forgetting the saved identity.
6. **Advertising but unusable gateway:** keep advertisements visible while forcing four successive
   GATT/CCCD/handshake failures. Confirm the counter is not reset by rediscovery and recovery pauses.
7. **Absent gateway:** leave the ESP32 off. Confirm four bounded attempts occur and recovery pauses;
   there must be no hot scan loop.
8. **Vehicle evidence:** only after the VHOS handshake passes, verify listen-only health, increasing
   CAN frame counts, zero bus-off events, and append-only persisted observations.

The current A/C recovery image remains `FIRMWARE NOT READY`; this work does not invent A/C data or
weaken that gate.
