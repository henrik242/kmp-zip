package no.synth.kmpzip.zip

import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import no.synth.kmpzip.crypto.AesExtraField
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Investigation tests for Zip4j interop behaviors.
 * Each test probes a specific aspect of Zip4j output to verify kmp-zip handles it correctly.
 */
class Zip4jInvestigationTest {

    private val password = "testpass"

    private fun readEntryContent(zis: ZipInputStream): String =
        zis.readBytes().decodeToString()

    // ---- 1. AES-128 with data descriptors ----
    // Note: Zip4j does NOT support AES-192 (throws "Invalid AES key strength"),
    // so only AES-128 and AES-256 can be tested here.

    @Test
    fun readZip4jAes128Deflated() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "aes128.txt"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_128
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.write("AES-128 content from Zip4j".toByteArray())
            zos.closeEntry()
        }

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry, "AES-128 entry should be readable")
        assertEquals("aes128.txt", entry.name)
        assertEquals("AES-128 content from Zip4j", readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    // ---- 2. AE-1 vs AE-2 version field ----

    @Test
    fun zip4jUsesAe2ByDefault() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "version-check.txt"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.write("Version check".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val aesField = findAesExtraFieldInLocalHeader(zipBytes)
        assertNotNull(aesField, "AES extra field should be present")
        assertEquals(2, aesField.version, "Zip4j should default to AE-2")

        val zis = ZipInputStream(zipBytes, password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("Version check", readEntryContent(zis))
        zis.close()
    }

    @Test
    fun zip4jAe1ExplicitVersion() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "ae1.txt"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                aesVersion = AesVersion.ONE
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.write("AE-1 version content".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val aesField = findAesExtraFieldInLocalHeader(zipBytes)
        assertNotNull(aesField)
        assertEquals(1, aesField.version, "Should be AE-1 when explicitly set")

        val zis = ZipInputStream(zipBytes, password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("AE-1 version content", readEntryContent(zis))
        zis.close()
    }

    // ---- 3. Legacy (ZipCrypto) encryption ----
    // BUG FOUND: kmp-zip cannot read Zip4j legacy-encrypted deflated entries.
    // Zip4j sets data descriptor flag (bit 3) AND writes compressedSize=0 in the
    // local header. kmp-zip sets legacyRemainingEncryptedBytes = Long.MAX_VALUE,
    // which means the inflater reads past the entry data into the data descriptor
    // and central directory, then finishEntry() tries to readDataDescriptor()
    // but the stream is already consumed, causing "Unexpected end of ZIP stream".

    // Legacy stored entries also fail for the same fundamental reason: the data
    // descriptor flag is set with compressedSize=0, and kmp-zip has no mechanism
    // to determine the end of encrypted stored data without knowing the size.

    // ---- 4. Large content (100KB) with data descriptors ----

    @Test
    fun readZip4jLargeAes256Deflated() {
        val largeContent = ByteArray(100_000) { (it % 256).toByte() }
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "large.bin"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.write(largeContent)
            zos.closeEntry()
        }

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("large.bin", entry.name)
        assertContentEquals(largeContent, zis.readBytes())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readZip4jLargeAesStored() {
        // Note: Zip4j encrypts data in-place, so we must copy the content before
        // passing it to Zip4j to prevent the original array from being corrupted.
        val expectedContent = ByteArray(100_000) { (it % 256).toByte() }
        val writeContent = expectedContent.copyOf()
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "large-stored.bin"
                compressionMethod = CompressionMethod.STORE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
                entrySize = writeContent.size.toLong()
            }
            zos.putNextEntry(params)
            zos.write(writeContent)
            zos.closeEntry()
        }

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("large-stored.bin", entry.name)
        assertContentEquals(expectedContent, zis.readBytes())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readZip4jLargeMultiEntry() {
        val content1 = ByteArray(50_000) { (it % 256).toByte() }
        val content2 = ByteArray(75_000) { ((it + 128) % 256).toByte() }
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params1 = ZipParameters().apply {
                fileNameInZip = "part1.bin"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params1)
            zos.write(content1)
            zos.closeEntry()

            val params2 = ZipParameters().apply {
                fileNameInZip = "part2.bin"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params2)
            zos.write(content2)
            zos.closeEntry()
        }

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("part1.bin", entry1.name)
        assertContentEquals(content1, zis.readBytes())

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("part2.bin", entry2.name)
        assertContentEquals(content2, zis.readBytes())

        assertNull(zis.nextEntry)
        zis.close()
    }

    // ---- 5. UTF-8 flag (bit 11) ----

    @Test
    fun zip4jSetsUtf8Flag() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "utf8-\u00e9\u00e8\u00ea.txt"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.write("UTF-8 filename test".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val flags = readLocalHeaderFlags(zipBytes)
        assertTrue((flags and 0x0800) != 0, "Zip4j should set UTF-8 flag for non-ASCII filenames")

        val zis = ZipInputStream(zipBytes, password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("utf8-\u00e9\u00e8\u00ea.txt", entry.name)
        assertEquals("UTF-8 filename test", readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun zip4jAsciiFilenameAlsoSetsUtf8Flag() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "simple.txt"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.write("ASCII filename test".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val flags = readLocalHeaderFlags(zipBytes)
        // Zip4j sets UTF-8 flag even for ASCII filenames
        assertTrue((flags and 0x0800) != 0, "Zip4j sets UTF-8 flag even for ASCII filenames")

        val zis = ZipInputStream(zipBytes, password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("simple.txt", entry.name)
        zis.close()
    }

    // ---- 6. Empty entries encrypted with Zip4j ----

    @Test
    fun zip4jEmptyAesDeflatedEntry() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "empty.txt"
                compressionMethod = CompressionMethod.DEFLATE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            }
            zos.putNextEntry(params)
            zos.closeEntry()
        }

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry, "Empty AES deflated entry should be readable")
        assertEquals("empty.txt", entry.name)
        val content = zis.readBytes()
        assertEquals(0, content.size, "Empty entry should have no content")
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun zip4jEmptyAesStoredEntry() {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, password.toCharArray()).use { zos ->
            val params = ZipParameters().apply {
                fileNameInZip = "empty-stored.txt"
                compressionMethod = CompressionMethod.STORE
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
                entrySize = 0
            }
            zos.putNextEntry(params)
            zos.closeEntry()
        }

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry, "Empty stored AES entry should be readable")
        assertEquals("empty-stored.txt", entry.name)
        val content = zis.readBytes()
        assertEquals(0, content.size, "Empty stored entry should have no content")
        assertNull(zis.nextEntry)
        zis.close()
    }

    // ---- 7. Reverse interop: kmp-zip writes, Zip4j reads ----

    @Test
    fun kmpZipWriteAesDeflatedZip4jRead() {
        val content = "Written by kmp-zip, read by Zip4j"
        val baos = no.synth.kmpzip.io.ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("kmpzip.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zip4jInput = net.lingala.zip4j.io.inputstream.ZipInputStream(
            java.io.ByteArrayInputStream(baos.toByteArray()),
            password.toCharArray()
        )
        val entry = zip4jInput.nextEntry
        assertNotNull(entry)
        assertEquals("kmpzip.txt", entry.fileName)
        assertEquals(content, zip4jInput.readAllBytes().decodeToString())
        assertNull(zip4jInput.nextEntry)
        zip4jInput.close()
    }

    @Test
    fun kmpZipWriteMultiEntryZip4jRead() {
        val entries = listOf(
            "first.txt" to "First kmp-zip entry",
            "second.txt" to "Second kmp-zip entry",
        )
        val baos = no.synth.kmpzip.io.ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        for ((name, content) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }
        zos.close()

        val zip4jInput = net.lingala.zip4j.io.inputstream.ZipInputStream(
            java.io.ByteArrayInputStream(baos.toByteArray()),
            password.toCharArray()
        )
        for ((expectedName, expectedContent) in entries) {
            val entry = zip4jInput.nextEntry
            assertNotNull(entry, "Expected entry: $expectedName")
            assertEquals(expectedName, entry.fileName)
            assertEquals(expectedContent, zip4jInput.readAllBytes().decodeToString())
        }
        assertNull(zip4jInput.nextEntry)
        zip4jInput.close()
    }

    @Test
    fun kmpZipWriteLegacyZip4jRead() {
        val content = "Legacy from kmp-zip"
        val baos = no.synth.kmpzip.io.ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("legacy.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zip4jInput = net.lingala.zip4j.io.inputstream.ZipInputStream(
            java.io.ByteArrayInputStream(baos.toByteArray()),
            password.toCharArray()
        )
        val entry = zip4jInput.nextEntry
        assertNotNull(entry)
        assertEquals("legacy.txt", entry.fileName)
        assertEquals(content, zip4jInput.readAllBytes().decodeToString())
        assertNull(zip4jInput.nextEntry)
        zip4jInput.close()
    }

    @Test
    fun kmpZipWriteStoredAesZip4jRead() {
        val content = "Stored AES from kmp-zip"
        val baos = no.synth.kmpzip.io.ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        val entry = ZipEntry("stored.txt")
        entry.method = ZipConstants.STORED
        entry.size = content.length.toLong()
        entry.compressedSize = content.length.toLong()
        zos.putNextEntry(entry)
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zip4jInput = net.lingala.zip4j.io.inputstream.ZipInputStream(
            java.io.ByteArrayInputStream(baos.toByteArray()),
            password.toCharArray()
        )
        val readEntry = zip4jInput.nextEntry
        assertNotNull(readEntry)
        assertEquals("stored.txt", readEntry.fileName)
        assertEquals(content, zip4jInput.readAllBytes().decodeToString())
        assertNull(zip4jInput.nextEntry)
        zip4jInput.close()
    }

    // ---- Helpers ----

    private fun findAesExtraFieldInLocalHeader(zip: ByteArray): AesExtraField? {
        if (zip.size < 30) return null
        val nameLen = (zip[26].toInt() and 0xFF) or ((zip[27].toInt() and 0xFF) shl 8)
        val extraLen = (zip[28].toInt() and 0xFF) or ((zip[29].toInt() and 0xFF) shl 8)
        if (extraLen == 0) return null
        val extraStart = 30 + nameLen
        val extra = zip.copyOfRange(extraStart, extraStart + extraLen)
        return AesExtraField.parse(extra)
    }

    private fun readLocalHeaderFlags(zip: ByteArray): Int {
        if (zip.size < 8) return 0
        return (zip[6].toInt() and 0xFF) or ((zip[7].toInt() and 0xFF) shl 8)
    }
}
