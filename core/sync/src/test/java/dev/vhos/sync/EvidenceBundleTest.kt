package dev.vhos.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vhos.protocol.GatewayFrame
import dev.vhos.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EvidenceBundleTest {
    private val envelope = GatewayFrame(
        messageType = MessageType.GATEWAY_HEALTH,
        sequence = 42u,
        monotonicMicroseconds = 9_001u,
        payload = "{\"contract\":\"gateway.health\"}".toByteArray(),
    ).encode()
    private val record = PortableEvidenceRecord(
        sourceRole = "OBD_CAN",
        sourceId = "esp32-test",
        sourceSequence = "42",
        sourceMonotonicMicroseconds = "9001",
        protocolMajor = 1,
        protocolMinor = 0,
        messageType = 4,
        flags = 0,
        ingestedAt = "2026-08-17T12:00:00Z",
        envelopeSha256 = EvidenceBundles.sha256(envelope),
        envelopeBase64 = Base64.getEncoder().encodeToString(envelope),
    )

    @Test
    fun checksummedBundleRoundTripsAndRetainsIdentity() {
        val bytes = EvidenceBundles.toByteArray(
            records = listOf(record),
            creator = BundleCreator("ANDROID", "dev.vhos.headunit", "0.1.0", "head-unit"),
            bundleId = UUID.fromString("7efec738-4535-4c66-9ec5-64bda8ed57fb"),
            createdAt = Instant.parse("2026-08-17T12:00:00Z"),
        )
        val imported = EvidenceBundles.fromByteArray(bytes)
        assertEquals("7efec738-4535-4c66-9ec5-64bda8ed57fb", imported.manifest.bundleId)
        assertEquals(1, imported.records.size)
        assertEquals("42", imported.records.single().sourceSequence)
        assertNull(imported.recoveryMetadata)
        assertFalse(imported.isLiveAuthority)
    }

    @Test
    fun writerAndReaderShareABoundedTwentyThousandRecordInterchangeWindow() {
        val error = assertThrows(BundleException::class.java) {
            EvidenceBundles.write(
                output = ByteArrayOutputStream(),
                records = List(20_001) { record },
                creator = BundleCreator("ANDROID", "dev.vhos.headunit", "0.1.0", "head-unit"),
            )
        }

        assertTrue(error.message.orEmpty().contains("too many records"))
    }

    @Test
    fun importsV2RecoveryMetadataWithoutGrantingLiveAuthority() {
        val archive = v1Archive()
        val v2 = rewriteManifest(archive) { manifest -> manifest.promoteToValidV2() }

        val imported = EvidenceBundles.fromByteArray(v2)

        assertEquals(EvidenceBundleManifest.CONTRACT_VERSION_V2, imported.manifest.contractVersion)
        assertEquals(RecoveryEvidenceMetadata.CLASSIFICATION, imported.recoveryMetadata?.classification)
        assertEquals(imported.manifest.segments.single().sha256, imported.recoveryMetadata?.sourceLedgerSha256)
        assertFalse(imported.recoveryMetadata!!.vehicleClaimsAuthorized)
        assertFalse(imported.isLiveAuthority)
        val manifestText = zipEntries(v2).getValue("manifest.json").toString(Charsets.UTF_8)
        assertTrue(manifestText.contains("\"classification\":\"RECOVERED_PORTABLE_EVIDENCE\""))
        assertTrue(manifestText.contains("\"vehicle_claims_authorized\":false"))
        assertEquals(EvidenceBundles.sha256(manifestText.toByteArray()), imported.manifestSha256)
    }

    @Test
    fun rejectsMissingOrInvalidV2RecoveryMetadata() {
        val invalidMutations: List<(JsonObject) -> Unit> = listOf(
            { manifest -> manifest.addProperty("contract_version", EvidenceBundleManifest.CONTRACT_VERSION_V2) },
            { manifest -> manifest.promoteToValidV2().getAsJsonObject("recovery").remove("classification") },
            { manifest -> manifest.promoteToValidV2().getAsJsonObject("recovery").addProperty("classification", "LIVE_VEHICLE_EVIDENCE") },
            { manifest -> manifest.promoteToValidV2().getAsJsonObject("recovery").remove("vehicle_claims_authorized") },
            { manifest -> manifest.promoteToValidV2().getAsJsonObject("recovery").addProperty("vehicle_claims_authorized", true) },
            { manifest -> manifest.promoteToValidV2().getAsJsonObject("recovery").remove("source_ledger_sha256") },
            { manifest -> manifest.promoteToValidV2().getAsJsonObject("recovery").addProperty("source_ledger_sha256", "A".repeat(64)) },
        )

        invalidMutations.forEach { mutation ->
            val invalid = rewriteManifest(v1Archive(), mutation)
            assertThrows(BundleException::class.java) {
                EvidenceBundles.fromByteArray(invalid)
            }
        }
    }

    @Test
    fun rejectsUnknownTopLevelAndRecoveryFieldsThatCouldSmuggleAuthority() {
        val extraRecoveryAuthority = rewriteManifest(v1Archive()) { manifest ->
            manifest.promoteToValidV2()
                .getAsJsonObject("recovery")
                .addProperty("live_vehicle_authority", true)
        }
        val extraTopLevelAuthority = rewriteManifest(v1Archive()) { manifest ->
            manifest.promoteToValidV2()
            manifest.addProperty("vehicle_claims_authorized", true)
        }
        val extraV1Field = rewriteManifest(v1Archive()) { manifest ->
            manifest.addProperty("recovery_classification", "RECOVERED_PORTABLE_EVIDENCE")
        }
        val extraCreatorAuthority = rewriteManifest(v1Archive()) { manifest ->
            manifest.getAsJsonObject("creator").addProperty("vehicle_claims_authorized", true)
        }
        val extraSegmentAuthority = rewriteManifest(v1Archive()) { manifest ->
            manifest.getAsJsonArray("segments").single().asJsonObject
                .addProperty("live_vehicle_authority", true)
        }

        listOf(
            extraRecoveryAuthority,
            extraTopLevelAuthority,
            extraV1Field,
            extraCreatorAuthority,
            extraSegmentAuthority,
        ).forEach { invalid ->
            assertThrows(BundleException::class.java) {
                EvidenceBundles.fromByteArray(invalid)
            }
        }
    }

    @Test
    fun rejectsV2WhenSourceLedgerDigestDoesNotMatchTheSingleLogicalFrameSegment() {
        val digestMismatch = rewriteManifest(v1Archive()) { manifest ->
            manifest.promoteToValidV2()
                .getAsJsonObject("recovery")
                .addProperty("source_ledger_sha256", "0".repeat(64))
        }
        val extraLedgerDeclaration = rewriteManifest(v1Archive()) { manifest ->
            manifest.promoteToValidV2()
            val duplicate = manifest.getAsJsonArray("segments").single().asJsonObject.deepCopy()
            duplicate.addProperty("path", "segments/recovered-logical-frames.ndjson")
            manifest.getAsJsonArray("segments").add(duplicate)
        }

        assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(digestMismatch)
        }
        assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(extraLedgerDeclaration)
        }
    }

    @Test
    fun manifestDigestBindsTheVerifiedRecoveryClassificationAndLedgerDigest() {
        val valid = rewriteManifest(v1Archive()) { manifest -> manifest.promoteToValidV2() }
        val validImport = EvidenceBundles.fromByteArray(valid)
        val tampered = rewriteManifest(valid) { manifest ->
            manifest.getAsJsonObject("recovery").addProperty("classification", "LIVE_VEHICLE_EVIDENCE")
        }

        assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(tampered)
        }
        assertNotEquals(
            validImport.manifestSha256,
            EvidenceBundles.sha256(zipEntries(tampered).getValue("manifest.json")),
        )
    }

    @Test
    fun rejectsDuplicateJsonKeysInManifestAndPortableRecord() {
        val duplicateManifestKey = rewriteManifestText(v1Archive()) { manifest ->
            manifest.replaceFirst("{", "{\"contract\":\"vhos.evidence-sync-bundle\",")
        }
        val duplicateNestedManifestKey = rewriteManifestText(v1Archive()) { manifest ->
            manifest.replaceFirst("\"creator\":{", "\"creator\":{\"platform\":\"IOS\",")
        }
        val duplicateRecordKey = rewriteLogicalFrameSegment(v1Archive()) { recordJson ->
            appendRecordMember(recordJson, "\"source_id\":\"authority-smuggling-shadow\"")
        }

        val manifestError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(duplicateManifestKey)
        }
        val recordError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(duplicateRecordKey)
        }
        val nestedManifestError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(duplicateNestedManifestKey)
        }
        assertTrue(manifestError.message.orEmpty().contains("duplicate JSON key 'contract'"))
        assertTrue(nestedManifestError.message.orEmpty().contains("duplicate JSON key 'platform'"))
        assertTrue(recordError.message.orEmpty().contains("duplicate JSON key 'source_id'"))
    }

    @Test
    fun requiresExactPortableRecordFieldsBeforeGsonCanIgnoreUnknownOrDefaultMissingFields() {
        val unknownAuthorityField = rewriteLogicalFrameSegment(v1Archive()) { recordJson ->
            appendRecordMember(recordJson, "\"vehicle_claims_authorized\":true")
        }
        val missingIngestionTime = rewriteLogicalFrameSegment(v1Archive()) { recordJson ->
            JsonParser.parseString(recordJson).asJsonObject.apply { remove("ingested_at") }.toString()
        }

        listOf(unknownAuthorityField, missingIngestionTime).forEach { invalid ->
            val error = assertThrows(BundleException::class.java) {
                EvidenceBundles.fromByteArray(invalid)
            }
            assertTrue(error.message.orEmpty().contains("missing or unsupported fields"))
        }
    }

    @Test
    fun rejectsPortableRecordScalarsOutsideTheSharedSchema() {
        val invalidMutations: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("source_role", "LIVE_AUTHORITY") },
            { it.addProperty("source_id", "") },
            { it.addProperty("source_id", "x".repeat(161)) },
            { it.addProperty("source_sequence", "042") },
            { it.addProperty("source_monotonic_microseconds", "-1") },
            { it.addProperty("protocol_major", "1") },
            { it.addProperty("protocol_major", 4_294_967_297L) },
            { it.addProperty("protocol_minor", 4_294_967_296L) },
            { it.addProperty("message_type", 4_294_967_300L) },
            { it.addProperty("flags", 4_294_967_296L) },
            { it.addProperty("protocol_minor", 256) },
            { it.addProperty("message_type", 0) },
            { it.addProperty("flags", 256) },
            { it.addProperty("ingested_at", "not-a-date-time") },
            { it.addProperty("ingested_at", "2026-02-30T12:00:00Z") },
            { it.addProperty("ingested_at", "2026-08-22T12:34:60Z") },
            { json -> json.addProperty("envelope_sha256", json.get("envelope_sha256").asString.uppercase()) },
        )

        invalidMutations.forEach { mutation ->
            val invalid = rewriteLogicalFrameSegment(v1Archive()) { recordJson ->
                JsonParser.parseString(recordJson).asJsonObject.also(mutation).toString()
            }
            assertThrows(BundleException::class.java) {
                EvidenceBundles.fromByteArray(invalid)
            }
        }
    }

    @Test
    fun acceptsTheSameCanonicalDecimalBoundsAndRfc3339WallTimeAsIos() {
        val boundaryFrame = GatewayFrame(
            messageType = MessageType.GATEWAY_HEALTH,
            sequence = ULong.MAX_VALUE,
            monotonicMicroseconds = ULong.MAX_VALUE,
            payload = "{\"contract\":\"gateway.health\"}".toByteArray(),
        ).encode()
        val interoperableRecord = record.copy(
            sourceId = "s".repeat(160),
            sourceSequence = ULong.MAX_VALUE.toString(),
            sourceMonotonicMicroseconds = ULong.MAX_VALUE.toString(),
            ingestedAt = "2026-08-22t12:00:00.123456789123-04:00",
            envelopeSha256 = EvidenceBundles.sha256(boundaryFrame),
            envelopeBase64 = Base64.getEncoder().encodeToString(boundaryFrame),
        )
        val archive = EvidenceBundles.toByteArray(
            records = listOf(interoperableRecord),
            creator = BundleCreator("IOS", "com.example.vhos", "2.0.0", "iPhone"),
        )

        val imported = EvidenceBundles.fromByteArray(archive)

        assertEquals(ULong.MAX_VALUE.toString(), imported.records.single().sourceSequence)
        assertEquals(
            "2026-08-22t12:00:00.123456789123-04:00",
            imported.records.single().ingestedAt,
        )
    }

    @Test
    fun acceptsIosManifestWallTimeLexicalVariantsAndRejectsImpossibleDates() {
        val compatible = rewriteManifest(v1Archive()) {
            it.addProperty("created_at", "2026-08-22t12:00:00.123456789123-04:00")
        }
        val impossible = rewriteManifest(v1Archive()) {
            it.addProperty("created_at", "2026-02-30T12:00:00Z")
        }

        assertEquals(
            "2026-08-22t12:00:00.123456789123-04:00",
            EvidenceBundles.fromByteArray(compatible).manifest.createdAt,
        )
        assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(impossible)
        }
    }

    @Test
    fun rejectsCreatorAndManifestScalarsOutsideTheSharedSchema() {
        val invalidMutations: List<(JsonObject) -> Unit> = listOf(
            { it.getAsJsonObject("creator").addProperty("application_id", "") },
            { it.getAsJsonObject("creator").addProperty("application_id", "x".repeat(161)) },
            { it.getAsJsonObject("creator").addProperty("application_version", "x".repeat(81)) },
            { it.getAsJsonObject("creator").addProperty("device_model", "x".repeat(161)) },
            { it.getAsJsonObject("creator").addProperty("application_id", 7) },
            { it.getAsJsonArray("segments").single().asJsonObject.addProperty("byte_count", "477") },
            { segment ->
                val declaration = segment.getAsJsonArray("segments").single().asJsonObject
                declaration.addProperty("sha256", declaration.get("sha256").asString.uppercase())
            },
        )

        invalidMutations.forEach { mutation ->
            assertThrows(BundleException::class.java) {
                EvidenceBundles.fromByteArray(rewriteManifest(v1Archive(), mutation))
            }
        }
    }

    @Test
    fun rejectsArchiveEntryCountAndDeclaredAggregateSegmentBytesBeforeRecordAccumulation() {
        val tooManyEntries = linkedMapOf<String, ByteArray>().apply {
            repeat(34) { index -> put("entry-$index", byteArrayOf()) }
        }
        val excessiveDeclaredBytes = rewriteManifest(v1Archive()) { manifest ->
            val segments = manifest.getAsJsonArray("segments")
            val first = segments.single().asJsonObject
            first.addProperty("byte_count", 9L * 1024 * 1024)
            val second = first.deepCopy()
            second.addProperty("path", "segments/logical-frames-2.ndjson")
            segments.add(second)
        }

        val entryError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(zip(tooManyEntries))
        }
        val declaredBytesError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(excessiveDeclaredBytes)
        }
        assertTrue(entryError.message.orEmpty().contains("too many archive entries"))
        assertTrue(declaredBytesError.message.orEmpty().contains("declared segment bytes"))
    }

    @Test
    fun rejectsDataSegmentManifestAggregateAndArchiveOutsideTheSharedWireProfile() {
        val oversizedDataSegment = zip(
            mapOf(
                "segments/logical-frames.ndjson" to
                    ByteArray(16 * 1024 * 1024 + 1) { '\n'.code.toByte() },
            )
        )
        val oversizedManifest = zip(
            mapOf("manifest.json" to ByteArray(1024 * 1024 + 1) { ' '.code.toByte() })
        )
        val compressedZipBomb = zeroFilledZip(
            8 * 1024 * 1024,
            9 * 1024 * 1024 + 1,
        )
        val oversizedArchive = ByteArray(18 * 1024 * 1024 + 1).also { bytes ->
            v1Archive().copyInto(bytes)
        }

        val segmentError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(oversizedDataSegment)
        }
        val manifestError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(oversizedManifest)
        }
        val aggregateError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(compressedZipBomb)
        }
        val archiveError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(oversizedArchive)
        }

        assertTrue(segmentError.message.orEmpty().contains("data segment exceeds"))
        assertTrue(manifestError.message.orEmpty().contains("manifest exceeds"))
        assertTrue(aggregateError.message.orEmpty().contains("aggregate uncompressed size"))
        assertTrue(archiveError.message.orEmpty().contains("total archive size"))
    }

    @Test
    fun acceptsADataSegmentAtTheSixteenMiBBoundary() {
        val archive = rewriteSegmentBytes(v1Archive()) { original ->
            ByteArray(16 * 1024 * 1024 - original.size) { '\n'.code.toByte() } + original
        }

        val imported = EvidenceBundles.fromByteArray(archive)

        assertEquals(16L * 1024 * 1024, imported.manifest.segments.single().byteCount)
        assertEquals(1, imported.records.size)
    }

    @Test
    fun rejectsWhitespaceOnlyNdjsonLinesInsteadOfSilentlyChangingTheRecordCount() {
        val nonCanonical = rewriteSegmentBytes(v1Archive()) { original ->
            "  \t\n".toByteArray() + original
        }

        val error = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(nonCanonical)
        }

        assertTrue(error.message.orEmpty().contains("JSON"))
    }

    @Test
    fun rejectsMalformedUtf8InManifestAndSegment() {
        val invalidManifest = zipEntries(v1Archive()).also { entries ->
            entries["manifest.json"] = byteArrayOf(0xC3.toByte(), 0x28)
        }.let(::zip)
        val invalidSegment = rewriteSegmentBytes(v1Archive()) {
            byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte())
        }

        val manifestError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(invalidManifest)
        }
        val segmentError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(invalidSegment)
        }
        assertTrue(manifestError.message.orEmpty().contains("not valid UTF-8"))
        assertTrue(segmentError.message.orEmpty().contains("not valid UTF-8"))
    }

    @Test
    fun rejectsExcessiveJsonNestingAndCollectionMembersDuringStrictParsing() {
        val excessiveNesting = rewriteManifest(v1Archive()) { manifest ->
            var cursor = JsonObject()
            manifest.add("unexpected_nested_value", cursor)
            repeat(17) {
                val child = JsonObject()
                cursor.add("child", child)
                cursor = child
            }
        }
        val excessiveMembers = rewriteManifest(v1Archive()) { manifest ->
            manifest.add(
                "unexpected_large_object",
                JsonObject().apply {
                    repeat(65) { index -> addProperty("member_$index", index) }
                },
            )
        }

        val nestingError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(excessiveNesting)
        }
        val membersError = assertThrows(BundleException::class.java) {
            EvidenceBundles.fromByteArray(excessiveMembers)
        }

        assertTrue(nestingError.message.orEmpty().contains("JSON nesting limit"))
        assertTrue(membersError.message.orEmpty().contains("JSON object-member limit"))
    }

    @Test
    fun rejectsTamperedSegmentBeforeReturningRecords() {
        val valid = EvidenceBundles.toByteArray(
            records = listOf(record),
            creator = BundleCreator("ANDROID", "dev.vhos.headunit", "0.1.0", "head-unit"),
        )
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(valid)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val path = "segments/logical-frames.ndjson"
        entries[path] = entries.getValue(path) + "tampered".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertThrows(BundleException::class.java) {
            EvidenceBundles.read(ByteArrayInputStream(output.toByteArray()))
        }
    }

    @Test
    fun importsGoldenBundleWrittenByIos() {
        val archive = Base64.getDecoder().decode(IOS_GOLDEN_BUNDLE_BASE64)
        val imported = EvidenceBundles.fromByteArray(archive)
        assertEquals("IOS", imported.manifest.creator.platform)
        assertEquals("7EFEC738-4535-4C66-9EC5-64BDA8ED57FB".lowercase(), imported.manifest.bundleId.lowercase())
        assertEquals("esp32-test", imported.records.single().sourceId)
        assertEquals("42", imported.records.single().sourceSequence)
    }

    private fun v1Archive(): ByteArray = EvidenceBundles.toByteArray(
        records = listOf(record),
        creator = BundleCreator("IOS", "com.example.vhos", "2.0.0", "iPhone"),
        bundleId = UUID.fromString("991d57b7-f816-40a0-8340-aa7a54dcc36a"),
        createdAt = Instant.parse("2026-08-22T12:00:00Z"),
    )

    private fun JsonObject.promoteToValidV2(): JsonObject {
        addProperty("contract_version", EvidenceBundleManifest.CONTRACT_VERSION_V2)
        val segmentSha256 = getAsJsonArray("segments").single().asJsonObject.get("sha256").asString
        add(
            "recovery",
            JsonObject().apply {
                addProperty("classification", RecoveryEvidenceMetadata.CLASSIFICATION)
                addProperty("vehicle_claims_authorized", false)
                addProperty("source_ledger_sha256", segmentSha256)
            },
        )
        return this
    }

    private fun rewriteManifest(
        archive: ByteArray,
        mutation: (JsonObject) -> Unit,
    ): ByteArray {
        val entries = zipEntries(archive)
        val manifest = JsonParser.parseString(
            entries.getValue("manifest.json").toString(Charsets.UTF_8),
        ).asJsonObject
        mutation(manifest)
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    private fun rewriteManifestText(
        archive: ByteArray,
        mutation: (String) -> String,
    ): ByteArray {
        val entries = zipEntries(archive)
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        entries["manifest.json"] = mutation(manifest).toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    private fun rewriteLogicalFrameSegment(
        archive: ByteArray,
        mutation: (String) -> String,
    ): ByteArray {
        val entries = zipEntries(archive)
        val segmentPath = "segments/logical-frames.ndjson"
        val originalRecord = entries.getValue(segmentPath).toString(Charsets.UTF_8).trimEnd()
        val segmentBytes = (mutation(originalRecord) + "\n").toByteArray(Charsets.UTF_8)
        entries[segmentPath] = segmentBytes
        val manifest = JsonParser.parseString(
            entries.getValue("manifest.json").toString(Charsets.UTF_8),
        ).asJsonObject
        manifest.getAsJsonArray("segments").single().asJsonObject.apply {
            addProperty("sha256", EvidenceBundles.sha256(segmentBytes))
            addProperty("byte_count", segmentBytes.size)
        }
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    private fun rewriteSegmentBytes(
        archive: ByteArray,
        mutation: (ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = zipEntries(archive)
        val segmentPath = "segments/logical-frames.ndjson"
        val segmentBytes = mutation(entries.getValue(segmentPath))
        entries[segmentPath] = segmentBytes
        val manifest = JsonParser.parseString(
            entries.getValue("manifest.json").toString(Charsets.UTF_8),
        ).asJsonObject
        manifest.getAsJsonArray("segments").single().asJsonObject.apply {
            addProperty("sha256", EvidenceBundles.sha256(segmentBytes))
            addProperty("byte_count", segmentBytes.size)
        }
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    private fun appendRecordMember(recordJson: String, member: String): String =
        recordJson.removeSuffix("}") + ",$member}"

    private fun zipEntries(archive: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun zeroFilledZip(vararg entrySizes: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val block = ByteArray(16 * 1024) { '\n'.code.toByte() }
        ZipOutputStream(output).use { zip ->
            entrySizes.forEachIndexed { index, size ->
                zip.putNextEntry(ZipEntry("entry-$index"))
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(block.size, remaining)
                    zip.write(block, 0, count)
                    remaining -= count
                }
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    companion object {
        private const val IOS_GOLDEN_BUNDLE_BASE64 =
            "UEsDBBQAAAAAAAAAAADOeK5f6gEAAOoBAAANAAAAbWFuaWZlc3QuanNvbnsiYnVuZGxlX2lkIjoiN0VGRUM3MzgtNDUzNS00QzY2LTlFQzUtNjRCREE4RUQ1N0ZCIiwiY29udHJhY3QiOiJ2aG9zLmV2aWRlbmNlLXN5bmMtYnVuZGxlIiwiY29udHJhY3RfdmVyc2lvbiI6IjEuMC4wIiwiY3JlYXRlZF9hdCI6IjIwMjYtMDgtMTdUMTI6MDA6MDBaIiwiY3JlYXRvciI6eyJhcHBsaWNhdGlvbl9pZCI6ImNvbS5pc2FpYWhkdXByZWUuVmVoaWNsZUhlYWx0aE9TIiwiYXBwbGljYXRpb25fdmVyc2lvbiI6IjAuMy4xIiwiZGV2aWNlX21vZGVsIjoiaVBob25lIiwicGxhdGZvcm0iOiJJT1MifSwic2VnbWVudHMiOlt7ImJ5dGVfY291bnQiOjQ3NywibWVkaWFfdHlwZSI6ImFwcGxpY2F0aW9uL3gtbmRqc29uIiwicGF0aCI6InNlZ21lbnRzL2xvZ2ljYWwtZnJhbWVzLm5kanNvbiIsInJlY29yZF9jb3VudCI6MSwic2hhMjU2IjoiYTY2OTVjMWU5YTZlNTU0Mjk0ZDU4YmQ1Y2MzODM2MTMyMzdmODdkYmEzZDJhMjU5ZGE5NmM1NWQyMjNhMmQ3MSJ9XX1QSwMEFAAAAAAAAAAAABvWj9LdAQAA3QEAAB4AAABzZWdtZW50cy9sb2dpY2FsLWZyYW1lcy5uZGpzb257ImNvbnRyYWN0Ijoidmhvcy5wb3J0YWJsZS1sb2dpY2FsLWZyYW1lIiwiY29udHJhY3RfdmVyc2lvbiI6IjEuMC4wIiwiZW52ZWxvcGVfYmFzZTY0IjoiVmtoUFV3RUFCQUFkQUFBQUtnQUFBQUFBQUFBcEl3QUFBQUFBQUJaOFl3ZlVieEhmZXlKamIyNTBjbUZqZENJNkltZGhkR1YzWVhrdWFHVmhiSFJvSW4wPSIsImVudmVsb3BlX3NoYTI1NiI6ImFmOGEyMWIwYTgwOTFiNjM3YTU2YjZlYmUxMGYwNGFlZmFkMWZmN2E0NDRlNzEyZjc5ZmI0ZjlhZThkMzQ4ZTIiLCJmbGFncyI6MCwiaW5nZXN0ZWRfYXQiOiIyMDI2LTA4LTE3VDEyOjAwOjAwWiIsIm1lc3NhZ2VfdHlwZSI6NCwicHJvdG9jb2xfbWFqb3IiOjEsInByb3RvY29sX21pbm9yIjowLCJzb3VyY2VfaWQiOiJlc3AzMi10ZXN0Iiwic291cmNlX21vbm90b25pY19taWNyb3NlY29uZHMiOiI5MDAxIiwic291cmNlX3JvbGUiOiJPQkRfQ0FOIiwic291cmNlX3NlcXVlbmNlIjoiNDIifQpQSwECFAAUAAAAAAAAAAAAzniuX+oBAADqAQAADQAAAAAAAAAAAAAAAAAAAAAAbWFuaWZlc3QuanNvblBLAQIUABQAAAAAAAAAAAAb1o/S3QEAAN0BAAAeAAAAAAAAAAAAAAAAABUCAABzZWdtZW50cy9sb2dpY2FsLWZyYW1lcy5uZGpzb25QSwUGAAAAAAIAAgCHAAAALgQAAAAA"
    }
}
