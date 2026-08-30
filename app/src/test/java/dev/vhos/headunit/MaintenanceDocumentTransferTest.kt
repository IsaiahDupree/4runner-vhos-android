package dev.vhos.headunit

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceDocumentTransferTest {
    @Test
    fun boundedReaderPreservesExactBytesAtLimit() {
        val bytes = ByteArray(8_193) { index -> (index % 251).toByte() }
        assertArrayEquals(
            bytes,
            MaintenanceDocumentTransfer.readBounded(ByteArrayInputStream(bytes), bytes.size),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun boundedReaderRejectsOneByteOverLimit() {
        MaintenanceDocumentTransfer.readBounded(
            ByteArrayInputStream(ByteArray(8_194)),
            8_193,
        )
    }

    @Test
    fun providerMediaTypeIsCanonicalOrSafelyGeneric() {
        assertEquals("image/jpeg", MaintenanceDocumentTransfer.canonicalMediaType("IMAGE/JPEG; charset=binary"))
        assertEquals("application/octet-stream", MaintenanceDocumentTransfer.canonicalMediaType("bad type"))
        assertEquals("application/octet-stream", MaintenanceDocumentTransfer.canonicalMediaType(null))
    }

    @Test
    fun archiveNameIsPortableAndTargetSpecific() {
        val name = MaintenanceDocumentTransfer.archiveFileName("2005 4Runner / V8", 1234)
        assertEquals("vhos-2005-4runner-v8-maintenance-1234.vhosmaintenance", name)
        assertTrue(name.length < 100)
    }
}
