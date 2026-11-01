package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.Crc32
import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ZipInputStreamVerifyCentralDirectoryTest {

    private fun strictNames(data: ByteArray, password: String? = null, read: Boolean = true): List<String> =
        ZipInputStream(ByteArrayInputStream(data), password?.encodeToByteArray(), verifyCentralDirectory = true).use { zis ->
            val names = mutableListOf<String>()
            while (true) {
                val e = zis.nextEntry ?: break
                if (read) zis.readBytes()
                names.add(e.name)
            }
            names
        }

    private fun assertStrictReads(data: ByteArray, password: String? = null) {
        val expected = ZipFile(data, password?.encodeToByteArray()).use { zip -> zip.entries.map { it.name } }
        assertEquals(expected, strictNames(data, password, read = true))
        assertEquals(expected, strictNames(data, password, read = false))
    }

    private fun abcZip(method: Int = ZipConstants.DEFLATED): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.setMethod(method)
            for (name in listOf("a", "b", "c")) {
                val data = name.encodeToByteArray()
                val entry = ZipEntry(name)
                if (method == ZipConstants.STORED) {
                    entry.size = data.size.toLong()
                    entry.crc = Crc32().also { it.update(data, 0, data.size) }.value()
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun indexOfSig(bytes: ByteArray, b2: Int, b3: Int, from: Int = 0): Int =
        (from..bytes.size - 4).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                bytes[it + 2] == b2.toByte() && bytes[it + 3] == b3.toByte()
        }

    private fun cdStart(bytes: ByteArray) = indexOfSig(bytes, 1, 2)
    private fun eocdStart(bytes: ByteArray) = indexOfSig(bytes, 5, 6)

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }
    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }

    private fun assertStrictFails(data: ByteArray): ZipFormatException =
        assertFailsWith<ZipFormatException> { strictNames(data) }

    // -- valid archives --

    @Test
    fun validArchivesPass() {
        val plain = listOf(
            TestData.storedZip, TestData.deflatedZip, TestData.multiEntryZip, TestData.directoryZip,
            TestData.binaryZip, TestData.cliStoredZip, TestData.cliDeflatedZip, TestData.cliWithDirZip,
            TestData.cliMixedZip, TestData.sevenStoredZip, TestData.sevenDeflatedZip,
            TestData.infozipZip64Zip, TestData.pythonZip64DdZip,
            abcZip(), abcZip(ZipConstants.STORED),
        )
        for (data in plain) assertStrictReads(data)
    }

    @Test
    fun encryptedArchivesPass() {
        val aes = listOf(
            TestData.aes128DeflatedZip, TestData.aes256BinaryZip, TestData.aes256DeflatedZip,
            TestData.aes256MultiZip, TestData.aes256StoredZip,
            TestData.legacyStoredZip, TestData.legacyDeflatedZip, TestData.legacyBinaryZip, TestData.legacyMultiZip,
        )
        for (data in aes) assertStrictReads(data, "password")
        assertStrictReads(TestData.zip4jAes256DeflatedZip, "123456")
    }

    @Test
    fun streamedEncryptedEntriesWithDataDescriptorsPass() {
        val data = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out, "pw".encodeToByteArray()).use { zip ->
                for (name in listOf("a", "b")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(name.repeat(100).encodeToByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        assertStrictReads(data, "pw")
    }

    @Test
    fun emptyArchivePasses() {
        assertEquals(emptyList(), strictNames(TestData.emptyZip))
    }

    @Test
    fun zip64EndRecordsPass() {
        // Rewrite the EOCD as ZIP64 record + locator + EOCD with marked fields.
        val bytes = abcZip()
        val cd = cdStart(bytes)
        val eocd = eocdStart(bytes)
        val cdSize = (eocd - cd).toLong()
        val record = byteArrayOf(0x50, 0x4b, 6, 6) + le64(44) + le16(45) + le16(45) + le32(0) + le32(0) +
            le64(3) + le64(3) + le64(cdSize) + le64(cd.toLong())
        val locator = byteArrayOf(0x50, 0x4b, 6, 7) + le32(0) + le64(eocd.toLong()) + le32(1)
        val end = byteArrayOf(0x50, 0x4b, 5, 6) + le16(0) + le16(0) + le16(0xFFFF) + le16(0xFFFF) +
            le32(0xFFFFFFFFL) + le32(0xFFFFFFFFL) + le16(0)
        assertStrictReads(bytes.copyOf(eocd) + record + locator + end)
    }

    @Test
    fun apkSigningBlockBeforeCentralDirectoryPasses() {
        val bytes = abcZip()
        val cd = cdStart(bytes)
        val size = 64L
        val pairLen = size - 24 - 8
        val block = le64(size) + le64(pairLen) + byteArrayOf(0x1a, 0x87.toByte(), 0x09, 0x71) +
            ByteArray((pairLen - 4).toInt()) + le64(size) + "APK Sig Block 42".encodeToByteArray()
        // The EOCD's central directory offset moves past the block.
        val patched = bytes.copyOf(cd) + block + bytes.copyOfRange(cd, bytes.size)
        val eocd = eocdStart(patched)
        le32((cd + block.size).toLong()).copyInto(patched, eocd + 16)
        assertEquals(listOf("a", "b", "c"), strictNames(patched))
    }

    // -- damaged archives --

    @Test
    fun droppedLocalEntryThrows() {
        val bytes = abcZip()
        val b = indexOfSig(bytes, 3, 4, from = 1)
        val c = indexOfSig(bytes, 3, 4, from = b + 1)
        val dropped = bytes.copyOf(b) + bytes.copyOfRange(c, bytes.size)
        assertStrictFails(dropped)
        assertEquals(listOf("a", "c"), ZipInputStream(dropped).use { zis -> generateSequence { zis.nextEntry?.name }.toList() })
    }

    @Test
    fun mismatchedNameThrows() {
        val bytes = abcZip()
        val cd = cdStart(bytes)
        bytes[cd + 46] = 'x'.code.toByte()
        val e = assertStrictFails(bytes)
        assertTrue(e.message.orEmpty().contains("name"), e.message)
        // Not verifying, the central directory is never read.
        assertEquals(3, ZipInputStream(bytes).use { zis -> generateSequence { zis.nextEntry }.count() })
    }

    @Test
    fun mismatchedCrcThrows() {
        val bytes = abcZip()
        val cd = cdStart(bytes)
        bytes[cd + 16] = (bytes[cd + 16] + 1).toByte()
        val e = assertStrictFails(bytes)
        assertTrue(e.message.orEmpty().contains("CRC"), e.message)
    }

    @Test
    fun mismatchedEntryCountThrows() {
        val bytes = abcZip()
        val eocd = eocdStart(bytes)
        bytes[eocd + 8] = 2
        bytes[eocd + 10] = 2
        assertStrictFails(bytes)
    }

    @Test
    fun missingCentralDirectoryThrows() {
        val bytes = abcZip()
        assertStrictFails(bytes.copyOf(cdStart(bytes)))
        // Entries followed straight by the EOCD.
        assertStrictFails(bytes.copyOf(cdStart(bytes)) + bytes.copyOfRange(eocdStart(bytes), bytes.size))
    }

    @Test
    fun truncatedCentralDirectoryThrows() {
        val bytes = abcZip()
        assertStrictFails(bytes.copyOf(cdStart(bytes) + 50))
        assertStrictFails(bytes.copyOf(eocdStart(bytes)))
    }

    @Test
    fun truncatedEndOfCentralDirectoryThrows() {
        val bytes = abcZip()
        assertStrictFails(bytes.copyOf(bytes.size - 5))
    }

    @Test
    fun junkBeforeCentralDirectoryThrows() {
        val bytes = abcZip()
        val cd = cdStart(bytes)
        val patched = bytes.copyOf(cd) + ByteArray(32) { 0x11 } + bytes.copyOfRange(cd, bytes.size)
        assertStrictFails(patched)
        assertEquals(3, ZipInputStream(patched).use { zis -> generateSequence { zis.nextEntry }.count() })
    }

    @Test
    fun failureIsThrownAgainOnNextCall() {
        val bytes = abcZip()
        bytes[cdStart(bytes) + 46] = 'x'.code.toByte()
        ZipInputStream(ByteArrayInputStream(bytes), verifyCentralDirectory = true).use { zis ->
            repeat(3) { zis.nextEntry }
            val first = assertFailsWith<ZipFormatException> { zis.nextEntry }
            val second = assertFailsWith<ZipFormatException> { zis.nextEntry }
            assertSame(first, second)
        }
    }
}
