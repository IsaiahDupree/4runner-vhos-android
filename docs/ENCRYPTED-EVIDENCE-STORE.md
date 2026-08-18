# Encrypted evidence truth store

## Outcome

Android build `0.1.0-dev.8` replaces framework SQLite with SQLCipher for every local evidence,
vehicle-profile, inventory, and health-assessment page. A random database passphrase is authenticated
and encrypted by a non-exportable Android Keystore AES-256-GCM key. Neither the database passphrase
nor the Keystore key is written in plaintext.

The app and connected-device service both enter through `EvidenceDatabase.open(...)`. There is no
second plaintext database opener in production code. The process-wide opener performs key recovery,
interrupted-migration recovery, page verification, and schema migration before BLE acquisition may
start.

## Cryptographic construction

| Layer | Construction | Persisted material |
| --- | --- | --- |
| Database | SQLCipher for Android 4.18.0 | Encrypted `vhos-evidence.db` pages and WAL |
| Database passphrase | 256 bits from `SecureRandom`, Base64url encoded | Never directly persisted |
| Wrapping key | Android Keystore AES-256 | Non-exportable Keystore entry |
| Key envelope | AES/GCM/NoPadding, random provider IV, 128-bit tag, versioned AAD | Version, IV, authenticated ciphertext, creation time |

The head unit must acquire evidence unattended after an owner starts a session, so the wrapping key
does not require per-use biometric or lock-screen authentication. This protects the secret from
ordinary filesystem extraction; it does not claim to defeat a rooted, actively compromised device
or an attacker operating inside the unlocked VHOS process.

App backup and device-transfer rules exclude databases, shared preferences, files, and external
state. Copying only the encrypted database to another Android device therefore does not copy the
Keystore key. Owner-controlled, checksummed VHOS exports remain the portable backup boundary.

## Fail-closed key behavior

The key envelope is complete only when all version, IV, ciphertext, and creation-time fields exist.

- An absent database and absent envelope create one new key and passphrase.
- An authoritative legacy plaintext database may create its first envelope for migration.
- A complete envelope must authenticate with the existing Keystore alias.
- Existing encrypted or interrupted-migration files without an envelope block before key creation.
- A partial envelope blocks the store.
- An envelope whose Keystore alias is missing blocks the store.
- An authentication or passphrase-shape failure blocks the store.
- No failure path replaces the key, deletes the database, or silently creates an empty truth store.

The UI reports `LOCAL EVIDENCE LOCKED` and BLE session startup stops when this boundary fails.

## Plaintext-to-encrypted migration

The installed development build may already contain append-only schema-v1 or schema-v2 framework
SQLite evidence. The migration keeps that file authoritative until an encrypted candidate proves it
is an exact usable replacement.

1. Open the plaintext database through SQLCipher with an empty key.
2. fully checkpoint WAL and switch the source to delete-journal mode;
3. fingerprint `user_version`, every non-internal schema object, and every user-table row count;
4. attach a new keyed candidate and run SQLCipher's `sqlcipher_export()`;
5. restore the exact `user_version` on the candidate;
6. require an encrypted header, active `cipher_version`, successful `quick_check`, exact schema and
   row-count equality, and rejection by an unkeyed SQLite connection;
7. atomically rename the plaintext file to `.plaintext.backup`;
8. atomically rename the verified candidate to the live database name;
9. repeat the keyed integrity and fingerprint checks on the activated file; and
10. only then remove the plaintext backup and all source journal/WAL sidecars.

No table is interpreted or rebuilt by application code during encryption. Existing schema migration
continues inside `SQLiteOpenHelper` after the encrypted page migration, so the prior v1-to-v2 path
remains non-destructive.

## Power-loss recovery states

All files live in the same private database directory so activation uses the filesystem's atomic
rename operation.

| Live file | Candidate | Plaintext backup | Recovery |
| --- | --- | --- | --- |
| plaintext | any | absent | Discard candidate and repeat export from the authoritative live file |
| absent | verified encrypted | matching plaintext | Activate candidate, verify again, remove backup |
| absent | absent | plaintext | Restore plaintext to the live name and repeat migration |
| verified encrypted | any | matching plaintext | Keep live encrypted file, remove candidate and backup |
| absent | encrypted | absent | Block as ambiguous |
| plaintext | any | plaintext | Block as ambiguous |
| encrypted | any | nonmatching plaintext | Block and preserve both |

Deletion failure after activation also blocks startup until cleanup succeeds. This prevents a
verified but forgotten plaintext copy from remaining in the app sandbox without being visible.

## Runtime and UI behavior

- Activity creation renders `LOCAL EVIDENCE SECURING` while a worker thread opens or migrates the
  store; the UI thread is not used for a potentially large export.
- Owner actions that need evidence remain unavailable until the secure store is ready.
- The foreground BLE service calls `startForeground()` first, then opens the same singleton store on
  a worker thread. BLE scanning begins only after the encrypted store is verified.
- The evidence card displays the runtime SQLCipher version, Keystore envelope version, and whether
  this process created, reused, migrated, or recovered the database.
- SQLCipher WAL remains enabled after activation; raw VHOS evidence is still persisted before any
  derived UI state.

## Verification

The Android instrumentation suite uses real framework SQLite, real SQLCipher native code, real
files, and a real Android Keystore entry. It covers:

- plaintext schema/data migration and keyed readback;
- plaintext-reader rejection;
- recovery after a simulated power loss between backup and activation; and
- authenticated key-envelope reuse plus missing-Keystore-key failure.

The instrumentation APK builds in CI/local development. It must still be executed on an online
Android device before publishing `dev.8`; the current Mac has no online emulator or ADB-connected
head unit. The repository's regular JVM tests additionally exercise exact plaintext-header
classification without substituting mock storage.

## Primary references

- [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android) — current artifact,
  supported API/ABIs, native loading, and opening APIs.
- [SQLCipher plaintext import test](https://github.com/sqlcipher/sqlcipher-android/blob/master/sqlcipher/src/androidTest/java/net/zetetic/database/sqlcipher/ImportUnencryptedDatabaseTest.java)
  — official `ATTACH ... KEY` plus `sqlcipher_export()` pattern.
- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore) —
  non-exportable app-scoped cryptographic keys.
- [`KeyGenParameterSpec.Builder`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
  — AES key purposes, GCM block mode, padding, key size, and randomized-encryption requirements.

## Next data-model gate

With owner evidence now encrypted at rest, the next schema migration may add the append-only service,
inspection, repair, replacement, part, receipt, photograph, cost, warranty, and component-baseline
ledger. That ledger must retain immutable source/evidence links and never overwrite prior lifecycle
records.
