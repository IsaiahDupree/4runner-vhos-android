package dev.vhos.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class EncryptedEvidenceStoreMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val databaseName = "vhos-encryption-instrumentation.db"
    private val preferencesName = "vhos-encryption-instrumentation-key"
    private val keyAlias = "dev.vhos.test.evidence-wrap.${System.nanoTime()}"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
        val main = context.getDatabasePath(databaseName)
        listOf(
            File(main.path + ".encrypted.pending"),
            File(main.path + ".plaintext.backup"),
            File(main.path + ".plaintext-fixture"),
        ).forEach { file ->
            listOf(file, File(file.path + "-journal"), File(file.path + "-shm"), File(file.path + "-wal"))
                .forEach(File::delete)
        }
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun migratesPlaintextWithoutChangingSchemaVersionOrRows() {
        val main = context.getDatabasePath(databaseName)
        createPlaintextFixture(main)
        val passphrase = testPassphrase()

        val outcome = EncryptedEvidenceStoreMigrator.prepare(context, databaseName, passphrase)

        assertEquals(EvidenceStoreMigrationState.PLAINTEXT_DATABASE_MIGRATED, outcome)
        assertFalse(EncryptedEvidenceStoreMigrator.hasPlaintextHeader(main))
        CipherSQLiteDatabase.openDatabase(
            main.absolutePath,
            passphrase,
            null,
            CipherSQLiteDatabase.OPEN_READONLY,
            null,
            null,
        ).use { encrypted ->
            assertEquals(2, encrypted.version)
            encrypted.rawQuery("SELECT value FROM evidence ORDER BY id", emptyArray<String>()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("first", cursor.getString(0))
                assertTrue(cursor.moveToNext())
                assertEquals("second", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        }

        var plaintextAccepted = false
        try {
            PlatformSQLiteDatabase.openDatabase(
                main.absolutePath,
                null,
                PlatformSQLiteDatabase.OPEN_READONLY,
            ).use { plaintext ->
                plaintext.rawQuery("SELECT COUNT(*) FROM sqlite_schema", null).use { it.moveToFirst() }
                plaintextAccepted = true
            }
        } catch (_: Exception) {
            // Required: the framework SQLite reader must reject SQLCipher pages.
        }
        assertFalse("Encrypted evidence was readable through plaintext SQLite", plaintextAccepted)
        passphrase.fill(0)
    }

    @Test
    fun recoversCrashBetweenPlaintextBackupAndEncryptedActivation() {
        val main = context.getDatabasePath(databaseName)
        val plaintextFixture = File(main.path + ".plaintext-fixture")
        createPlaintextFixture(main)
        main.copyTo(plaintextFixture, overwrite = false)
        val passphrase = testPassphrase()
        EncryptedEvidenceStoreMigrator.prepare(context, databaseName, passphrase)

        val pending = File(main.path + ".encrypted.pending")
        val backup = File(main.path + ".plaintext.backup")
        assertTrue(main.renameTo(pending))
        plaintextFixture.copyTo(backup, overwrite = false)
        assertTrue(main.notExists())

        val outcome = EncryptedEvidenceStoreMigrator.prepare(context, databaseName, passphrase)

        assertEquals(EvidenceStoreMigrationState.INTERRUPTED_MIGRATION_RECOVERED, outcome)
        assertTrue(main.exists())
        assertFalse(pending.exists())
        assertFalse(backup.exists())
        assertFalse(EncryptedEvidenceStoreMigrator.hasPlaintextHeader(main))
        passphrase.fill(0)
    }

    @Test
    fun keyEnvelopeRoundTripsAndFailsClosedWhenKeystoreEntryIsMissing() {
        val manager = EvidenceStoreKeyManager(context, preferencesName, keyAlias)
        val first = manager.getOrCreatePassphrase(allowEnvelopeCreation = true)
        val second = manager.getOrCreatePassphrase(allowEnvelopeCreation = false)
        assertArrayEquals(first, second)
        val preferenceText = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.joinToString("|")
        assertFalse(preferenceText.contains(first.toString(Charsets.US_ASCII)))

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        try {
            manager.getOrCreatePassphrase(allowEnvelopeCreation = false)
            fail("A missing wrapping key must not be silently replaced.")
        } catch (error: EvidenceStoreSecurityException) {
            assertTrue(error.message.orEmpty().contains("unavailable"))
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun absentEnvelopeCannotBeReplacedWhenEvidenceAlreadyExists() {
        val manager = EvidenceStoreKeyManager(context, preferencesName, keyAlias)

        try {
            manager.getOrCreatePassphrase(allowEnvelopeCreation = false)
            fail("Existing evidence without its key envelope must fail closed.")
        } catch (error: EvidenceStoreSecurityException) {
            assertTrue(error.message.orEmpty().contains("key envelope is absent"))
        }

        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(keyAlias))
    }

    private fun createPlaintextFixture(file: File) {
        file.parentFile?.mkdirs()
        PlatformSQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE evidence (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            database.execSQL("CREATE INDEX evidence_value ON evidence(value)")
            database.execSQL("INSERT INTO evidence(id, value) VALUES (1, 'first'), (2, 'second')")
            database.version = 2
        }
        assertTrue(EncryptedEvidenceStoreMigrator.hasPlaintextHeader(file))
    }

    private fun testPassphrase(): ByteArray = ByteArray(32)
        .also(SecureRandom()::nextBytes)
        .let { entropy ->
            Base64.getUrlEncoder().withoutPadding().encode(entropy).also { entropy.fill(0) }
        }

    private fun File.notExists(): Boolean = !exists()
}
