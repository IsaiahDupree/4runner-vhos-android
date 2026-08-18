package dev.vhos.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

class EvidenceStoreFileClassificationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recognizesOnlyTheCompletePlaintextSqliteHeader() {
        val plaintext = temporaryFolder.newFile("plain.db").apply {
            writeBytes("SQLite format 3\u0000payload".toByteArray(StandardCharsets.US_ASCII))
        }
        val encrypted = temporaryFolder.newFile("encrypted.db").apply {
            writeBytes(ByteArray(64) { index -> (index * 37 + 11).toByte() })
        }
        val truncated = temporaryFolder.newFile("truncated.db").apply {
            writeBytes("SQLite format".toByteArray(StandardCharsets.US_ASCII))
        }

        assertTrue(EncryptedEvidenceStoreMigrator.hasPlaintextHeader(plaintext))
        assertFalse(EncryptedEvidenceStoreMigrator.hasPlaintextHeader(encrypted))
        assertFalse(EncryptedEvidenceStoreMigrator.hasPlaintextHeader(truncated))
    }
}
