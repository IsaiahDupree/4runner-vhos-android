package dev.vhos.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class EvidenceStoreMigrationState(val displayName: String) {
    NEW_ENCRYPTED_DATABASE("new encrypted store"),
    EXISTING_ENCRYPTED_DATABASE("existing encrypted store"),
    PLAINTEXT_DATABASE_MIGRATED("plaintext store migrated and verified"),
    INTERRUPTED_MIGRATION_RECOVERED("interrupted migration recovered and verified"),
}

data class EvidenceStoreSecurity(
    val encryptedAtRest: Boolean,
    val cipherVersion: String,
    val keyProtection: String,
    val keyEnvelopeVersion: Int,
    val migrationState: EvidenceStoreMigrationState,
)

internal data class DatabaseFingerprint(
    val userVersion: Int,
    val schema: List<String>,
    val tableRows: Map<String, Long>,
)

internal object EvidenceStoreNativeLibrary {
    @Volatile
    private var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}

internal class EvidenceStoreKeyManager(
    context: Context,
    private val preferencesName: String = KEY_PREFERENCES,
    private val wrappingEntryName: String = WRAPPING_ENTRY_NAME,
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun getOrCreatePassphrase(allowEnvelopeCreation: Boolean): ByteArray = synchronized(KEY_LOCK) {
        val envelopeState = envelopeState()
        if (envelopeState == EnvelopeState.ABSENT && !allowEnvelopeCreation) {
            throw EvidenceStoreSecurityException(
                "Evidence-store files already exist, but their authenticated key envelope is absent. " +
                    "VHOS will not generate a replacement key or modify evidence."
            )
        }
        val keyStore = androidKeyStore()
        val wrappingKey = when {
            keyStore.containsAlias(wrappingEntryName) -> keyStore.getKey(wrappingEntryName, null) as? SecretKey
                ?: throw EvidenceStoreSecurityException("The evidence-store wrapping key has an invalid type.")
            envelopeState == EnvelopeState.ABSENT -> createWrappingKey()
            else -> throw EvidenceStoreSecurityException(
                "Encrypted evidence exists but its Android Keystore key is unavailable. " +
                    "VHOS will not replace the key or create a blank database."
            )
        }

        when (envelopeState) {
            EnvelopeState.ABSENT -> createPassphraseEnvelope(wrappingKey)
            EnvelopeState.COMPLETE -> decryptPassphraseEnvelope(wrappingKey)
            EnvelopeState.PARTIAL -> throw EvidenceStoreSecurityException(
                "The evidence-store key envelope is incomplete. VHOS is failing closed without modifying evidence."
            )
        }
    }

    private fun envelopeState(): EnvelopeState {
        val present = listOf(
            ENVELOPE_VERSION_KEY,
            ENVELOPE_IV_KEY,
            ENVELOPE_CIPHERTEXT_KEY,
            ENVELOPE_CREATED_AT_KEY,
        ).count(preferences::contains)
        return when (present) {
            0 -> EnvelopeState.ABSENT
            4 -> EnvelopeState.COMPLETE
            else -> EnvelopeState.PARTIAL
        }
    }

    private fun createPassphraseEnvelope(wrappingKey: SecretKey): ByteArray {
        val entropy = ByteArray(PASSPHRASE_ENTROPY_BYTES).also(SecureRandom()::nextBytes)
        val passphrase = Base64.getUrlEncoder().withoutPadding().encode(entropy)
        entropy.fill(0)
        val cipher = Cipher.getInstance(WRAP_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        cipher.updateAAD(KEY_ENVELOPE_AAD)
        val ciphertext = cipher.doFinal(passphrase)
        val committed = preferences.edit()
            .putInt(ENVELOPE_VERSION_KEY, KEY_ENVELOPE_VERSION)
            .putString(ENVELOPE_IV_KEY, Base64.getEncoder().encodeToString(cipher.iv))
            .putString(ENVELOPE_CIPHERTEXT_KEY, Base64.getEncoder().encodeToString(ciphertext))
            .putString(ENVELOPE_CREATED_AT_KEY, Instant.now().toString())
            .commit()
        ciphertext.fill(0)
        check(committed) {
            passphrase.fill(0)
            "Android did not durably commit the evidence-store key envelope."
        }
        return passphrase
    }

    private fun decryptPassphraseEnvelope(wrappingKey: SecretKey): ByteArray {
        val version = preferences.getInt(ENVELOPE_VERSION_KEY, -1)
        if (version != KEY_ENVELOPE_VERSION) {
            throw EvidenceStoreSecurityException(
                "Unsupported evidence-store key-envelope version $version; expected $KEY_ENVELOPE_VERSION."
            )
        }
        val iv = decodeEnvelopeField(ENVELOPE_IV_KEY)
        val ciphertext = decodeEnvelopeField(ENVELOPE_CIPHERTEXT_KEY)
        return try {
            val cipher = Cipher.getInstance(WRAP_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(KEY_ENVELOPE_AAD)
            cipher.doFinal(ciphertext).also(::validatePassphrase)
        } catch (error: Exception) {
            throw EvidenceStoreSecurityException(
                "The evidence-store key envelope could not be authenticated. Evidence was not opened or replaced.",
                error,
            )
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun decodeEnvelopeField(key: String): ByteArray {
        val encoded = preferences.getString(key, null)
            ?: throw EvidenceStoreSecurityException("The evidence-store key envelope is missing $key.")
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw EvidenceStoreSecurityException("The evidence-store key envelope contains invalid Base64.", error)
        }
    }

    private fun validatePassphrase(passphrase: ByteArray) {
        if (passphrase.size != PASSPHRASE_LENGTH || passphrase.any { byte ->
                val value = byte.toInt().toChar()
                !(value.isLetterOrDigit() || value == '-' || value == '_')
            }
        ) {
            passphrase.fill(0)
            throw EvidenceStoreSecurityException("The authenticated evidence-store passphrase has an invalid shape.")
        }
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    private fun createWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                wrappingEntryName,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(WRAPPING_KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private enum class EnvelopeState { ABSENT, COMPLETE, PARTIAL }

    companion object {
        const val KEY_ENVELOPE_VERSION = 1
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val WRAP_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_PREFERENCES = "vhos_evidence_store_key_v1"
        private const val WRAPPING_ENTRY_NAME = "dev.vhos.headunit.evidence-store-wrap.v1"
        private const val ENVELOPE_VERSION_KEY = "envelope_version"
        private const val ENVELOPE_IV_KEY = "envelope_iv"
        private const val ENVELOPE_CIPHERTEXT_KEY = "envelope_ciphertext"
        private const val ENVELOPE_CREATED_AT_KEY = "envelope_created_at"
        private const val PASSPHRASE_ENTROPY_BYTES = 32
        private const val PASSPHRASE_LENGTH = 43
        private const val WRAPPING_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private val KEY_ENVELOPE_AAD =
            "VHOS:EVIDENCE_STORE_KEY:1".toByteArray(StandardCharsets.UTF_8)
        private val KEY_LOCK = Any()
    }
}

internal object EncryptedEvidenceStoreMigrator {
    private val migrationLock = Any()
    private val plaintextHeader = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)

    /**
     * A new key envelope is safe only for a genuinely new store or an authoritative legacy
     * plaintext store. Encrypted or interrupted-migration files require their original envelope.
     */
    fun mayCreateKeyEnvelope(context: Context, databaseName: String): Boolean {
        val main = context.applicationContext.getDatabasePath(databaseName)
        val pending = File(main.path + PENDING_SUFFIX)
        val backup = File(main.path + PLAINTEXT_BACKUP_SUFFIX)
        return when {
            !main.exists() -> !pending.exists() && !backup.exists()
            hasPlaintextHeader(main) -> !backup.exists()
            else -> false
        }
    }

    fun prepare(
        context: Context,
        databaseName: String,
        passphrase: ByteArray,
    ): EvidenceStoreMigrationState = synchronized(migrationLock) {
        EvidenceStoreNativeLibrary.load()
        val main = context.applicationContext.getDatabasePath(databaseName)
        main.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw EvidenceStoreSecurityException("Unable to create the private database directory.")
            }
        }
        val pending = File(main.path + PENDING_SUFFIX)
        val backup = File(main.path + PLAINTEXT_BACKUP_SUFFIX)
        recoverInterruptedState(main, pending, backup, passphrase)?.let { return@synchronized it }

        if (!main.exists()) {
            if (pending.exists() || backup.exists()) {
                throw EvidenceStoreSecurityException(
                    "Evidence-store migration artifacts are ambiguous; no file was modified."
                )
            }
            return@synchronized EvidenceStoreMigrationState.NEW_ENCRYPTED_DATABASE
        }

        if (!hasPlaintextHeader(main)) {
            verifyEncryptedReadable(main, passphrase)
            deleteTemporaryEncryptedCandidate(pending)
            return@synchronized EvidenceStoreMigrationState.EXISTING_ENCRYPTED_DATABASE
        }

        migratePlaintext(main, pending, backup, passphrase)
        EvidenceStoreMigrationState.PLAINTEXT_DATABASE_MIGRATED
    }

    private fun recoverInterruptedState(
        main: File,
        pending: File,
        backup: File,
        passphrase: ByteArray,
    ): EvidenceStoreMigrationState? {
        if (main.exists()) {
            if (hasPlaintextHeader(main)) {
                if (backup.exists()) {
                    throw EvidenceStoreSecurityException(
                        "Both the live plaintext store and a plaintext migration backup exist; " +
                            "VHOS will not guess which is authoritative."
                    )
                }
                deleteTemporaryEncryptedCandidate(pending)
                return null
            }

            verifyEncryptedReadable(main, passphrase)
            deleteTemporaryEncryptedCandidate(pending)
            if (backup.exists()) {
                requireMatchingFingerprints(
                    plaintextFingerprint(backup),
                    encryptedFingerprint(main, passphrase, fullIntegrityCheck = true),
                )
                deletePlaintextBackup(backup)
                return EvidenceStoreMigrationState.INTERRUPTED_MIGRATION_RECOVERED
            }
            return null
        }

        if (backup.exists() && pending.exists()) {
            val source = plaintextFingerprint(backup)
            val candidate = encryptedFingerprint(pending, passphrase, fullIntegrityCheck = true)
            requireMatchingFingerprints(source, candidate)
            deleteAuxiliaryFiles(pending)
            atomicRename(pending, main)
            verifyEncryptedReadable(main, passphrase)
            deletePlaintextBackup(backup)
            return EvidenceStoreMigrationState.INTERRUPTED_MIGRATION_RECOVERED
        }

        if (backup.exists()) {
            atomicRename(backup, main)
            return null
        }

        if (pending.exists()) {
            throw EvidenceStoreSecurityException(
                "An encrypted migration candidate exists without its plaintext source or backup; " +
                    "VHOS is failing closed."
            )
        }
        return null
    }

    private fun migratePlaintext(
        main: File,
        pending: File,
        backup: File,
        passphrase: ByteArray,
    ) {
        require(!backup.exists()) { "A plaintext migration backup already exists." }
        deleteTemporaryEncryptedCandidate(pending)
        val source = consolidateAndFingerprintPlaintext(main)
        exportPlaintextToEncrypted(main, pending, passphrase, source.userVersion)
        val candidate = encryptedFingerprint(pending, passphrase, fullIntegrityCheck = true)
        requireMatchingFingerprints(source, candidate)

        deleteAuxiliaryFiles(main)
        deleteAuxiliaryFiles(pending)
        atomicRename(main, backup)
        try {
            atomicRename(pending, main)
        } catch (error: Exception) {
            if (!main.exists() && backup.exists()) atomicRename(backup, main)
            throw EvidenceStoreSecurityException(
                "Unable to atomically activate the encrypted evidence store; plaintext was restored.",
                error,
            )
        }

        try {
            val activated = encryptedFingerprint(main, passphrase, fullIntegrityCheck = true)
            requireMatchingFingerprints(source, activated)
            deletePlaintextBackup(backup)
        } catch (error: Exception) {
            throw EvidenceStoreSecurityException(
                "The encrypted store was not fully verified after activation. " +
                    "The plaintext backup was retained for deterministic recovery.",
                error,
            )
        }
    }

    private fun consolidateAndFingerprintPlaintext(databaseFile: File): DatabaseFingerprint {
        val database = openPlaintext(databaseFile, SQLiteDatabase.OPEN_READWRITE)
        return try {
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
            database.rawQuery("PRAGMA journal_mode=DELETE", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true)) {
                    "Plaintext evidence could not leave WAL mode before migration."
                }
            }
            fingerprint(database)
        } finally {
            database.close()
        }
    }

    private fun plaintextFingerprint(databaseFile: File): DatabaseFingerprint {
        check(hasPlaintextHeader(databaseFile)) { "Expected a plaintext SQLite migration source." }
        val database = openPlaintext(databaseFile, SQLiteDatabase.OPEN_READONLY)
        return try {
            assertQuickCheck(database)
            fingerprint(database)
        } finally {
            database.close()
        }
    }

    private fun exportPlaintextToEncrypted(
        sourceFile: File,
        destinationFile: File,
        passphrase: ByteArray,
        userVersion: Int,
    ) {
        val database = openPlaintext(sourceFile, SQLiteDatabase.OPEN_READWRITE)
        val passwordText = passphrase.toString(StandardCharsets.US_ASCII)
        try {
            database.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf(destinationFile.absolutePath, passwordText),
            )
            try {
                database.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                database.rawExecSQL("PRAGMA encrypted.user_version = $userVersion")
            } finally {
                database.execSQL("DETACH DATABASE encrypted")
            }
        } finally {
            database.close()
        }
    }

    private fun encryptedFingerprint(
        databaseFile: File,
        passphrase: ByteArray,
        fullIntegrityCheck: Boolean,
    ): DatabaseFingerprint {
        check(!hasPlaintextHeader(databaseFile)) {
            "The SQLCipher candidate still exposes the plaintext SQLite header."
        }
        val database = openEncrypted(databaseFile, passphrase, SQLiteDatabase.OPEN_READWRITE)
        return try {
            requireCipherVersion(database)
            if (fullIntegrityCheck) assertQuickCheck(database)
            fingerprint(database)
        } finally {
            database.close()
        }.also {
            assertPlaintextOpenRejected(databaseFile)
        }
    }

    private fun verifyEncryptedReadable(databaseFile: File, passphrase: ByteArray) {
        check(!hasPlaintextHeader(databaseFile)) {
            "The evidence-store file still has a plaintext SQLite header."
        }
        val database = openEncrypted(databaseFile, passphrase, SQLiteDatabase.OPEN_READONLY)
        try {
            requireCipherVersion(database)
            database.rawQuery("SELECT COUNT(*) FROM sqlite_schema", emptyArray()).use { cursor ->
                check(cursor.moveToFirst()) { "The encrypted evidence schema is unreadable." }
            }
        } finally {
            database.close()
        }
    }

    private fun fingerprint(database: SQLiteDatabase): DatabaseFingerprint {
        val userVersion = database.rawQuery("PRAGMA user_version", emptyArray()).use { cursor ->
            check(cursor.moveToFirst()) { "The evidence database has no user_version result." }
            cursor.getInt(0)
        }
        val schema = mutableListOf<String>()
        val tables = mutableListOf<String>()
        database.rawQuery(
            "SELECT type, name, tbl_name, COALESCE(sql, '') FROM sqlite_schema " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val name = cursor.getString(1)
                schema += listOf(type, name, cursor.getString(2), cursor.getString(3)).joinToString("\u001f")
                if (type == "table") tables += name
            }
        }
        val rows = linkedMapOf<String, Long>()
        tables.sorted().forEach { table ->
            val quoted = "\"${table.replace("\"", "\"\"")}\""
            rows[table] = database.rawQuery("SELECT COUNT(*) FROM $quoted", emptyArray()).use { cursor ->
                check(cursor.moveToFirst()) { "Unable to count evidence table $table." }
                cursor.getLong(0)
            }
        }
        return DatabaseFingerprint(userVersion, schema, rows)
    }

    private fun requireMatchingFingerprints(
        plaintext: DatabaseFingerprint,
        encrypted: DatabaseFingerprint,
    ) {
        if (plaintext != encrypted) {
            throw EvidenceStoreSecurityException(
                "The encrypted candidate does not exactly match the plaintext schema, version, and row counts."
            )
        }
    }

    private fun assertQuickCheck(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                "SQLite integrity verification did not return ok."
            }
        }
    }

    private fun requireCipherVersion(database: SQLiteDatabase): String =
        database.rawQuery("PRAGMA cipher_version", emptyArray()).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).isNotBlank()) {
                "SQLCipher did not report an active cipher version."
            }
            cursor.getString(0)
        }

    private fun assertPlaintextOpenRejected(databaseFile: File) {
        var database: SQLiteDatabase? = null
        var rejected = false
        try {
            database = openPlaintext(databaseFile, SQLiteDatabase.OPEN_READONLY)
            database.rawQuery("SELECT COUNT(*) FROM sqlite_schema", emptyArray()).use { cursor ->
                cursor.moveToFirst()
            }
        } catch (_: Exception) {
            rejected = true
        } finally {
            database?.close()
        }
        check(rejected) { "The encrypted evidence candidate was readable without its key." }
    }

    private fun openPlaintext(databaseFile: File, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            byteArrayOf(),
            null,
            flags,
            null,
            null,
        )

    private fun openEncrypted(databaseFile: File, passphrase: ByteArray, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            passphrase,
            null,
            flags,
            null,
            null,
        )

    private fun deleteTemporaryEncryptedCandidate(pending: File) {
        if (!pending.exists()) return
        deleteAuxiliaryFiles(pending)
        if (!pending.delete()) {
            throw EvidenceStoreSecurityException("Unable to remove a stale encrypted migration candidate.")
        }
    }

    private fun deletePlaintextBackup(backup: File) {
        deleteAuxiliaryFiles(backup)
        if (backup.exists() && !backup.delete()) {
            throw EvidenceStoreSecurityException(
                "Encrypted evidence is active, but the verified plaintext backup could not be removed."
            )
        }
    }

    private fun deleteAuxiliaryFiles(databaseFile: File) {
        listOf("-journal", "-shm", "-wal").forEach { suffix ->
            val auxiliary = File(databaseFile.path + suffix)
            if (auxiliary.exists() && !auxiliary.delete()) {
                throw EvidenceStoreSecurityException("Unable to remove database migration artifact ${auxiliary.name}.")
            }
        }
    }

    private fun atomicRename(source: File, destination: File) {
        check(source.exists()) { "Migration source ${source.name} does not exist." }
        check(!destination.exists()) { "Migration destination ${destination.name} already exists." }
        try {
            Os.rename(source.absolutePath, destination.absolutePath)
        } catch (error: Exception) {
            throw EvidenceStoreSecurityException(
                "Atomic evidence-store rename ${source.name} -> ${destination.name} failed.",
                error,
            )
        }
    }

    internal fun hasPlaintextHeader(file: File): Boolean {
        if (!file.exists() || file.length() < plaintextHeader.size) return false
        val header = ByteArray(plaintextHeader.size)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return false
                offset += count
            }
        }
        return header.contentEquals(plaintextHeader)
    }

    private const val PENDING_SUFFIX = ".encrypted.pending"
    private const val PLAINTEXT_BACKUP_SUFFIX = ".plaintext.backup"
}

class EvidenceStoreSecurityException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
