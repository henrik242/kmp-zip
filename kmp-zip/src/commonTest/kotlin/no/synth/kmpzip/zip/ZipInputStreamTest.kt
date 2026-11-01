package no.synth.kmpzip.zip

import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipInputStreamTest {

    @Test
    fun readsDeflatedEntryIntoNonZeroBufferOffset() {
        val expected = ZipInputStream(TestData.deflatedZip).use { zis ->
            assertNotNull(zis.nextEntry)
            zis.readBytes()
        }
        // One spare byte, so the read that finds the end asks for a byte and gets -1; a
        // zero-length read returns 0, even at the end.
        val out = ByteArray(expected.size + 10) { 0x2A }
        var total = 0
        ZipInputStream(TestData.deflatedZip).use { zis ->
            assertNotNull(zis.nextEntry)
            while (true) {
                val n = zis.read(out, 9 + total, minOf(64, out.size - 9 - total))
                if (n == -1) break
                total += n
            }
        }
        assertEquals(expected.size, total)
        assertContentEquals(expected, out.copyOfRange(9, 9 + total))
        for (i in 0 until 9) assertEquals(0x2A, out[i], "clobbered out[$i] before the offset")
    }

    private fun readEntryContent(zis: ZipInputStream): String {
        val buf = ByteArray(4096)
        val out = mutableListOf<Byte>()
        while (true) {
            val n = zis.read(buf, 0, buf.size)
            if (n == -1) break
            for (i in 0 until n) out.add(buf[i])
        }
        return out.toByteArray().decodeToString()
    }

    private fun readEntryBytes(zis: ZipInputStream): ByteArray {
        val buf = ByteArray(4096)
        val out = mutableListOf<Byte>()
        while (true) {
            val n = zis.read(buf, 0, buf.size)
            if (n == -1) break
            for (i in 0 until n) out.add(buf[i])
        }
        return out.toByteArray()
    }

    @Test
    fun singleStoredEntry() {
        val zis = ZipInputStream(TestData.storedZip)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.STORED, entry.method)

        val content = readEntryContent(zis)
        assertEquals("Hello, World!", content)

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun singleDeflatedEntry() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.DEFLATED, entry.method)

        val content = readEntryContent(zis)
        assertEquals("Hello, World!", content)

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun multipleEntries() {
        val zis = ZipInputStream(TestData.multiEntryZip)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("file1.txt", entry1.name)
        val content1 = readEntryContent(zis)
        assertEquals("First file content", content1)

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("file2.txt", entry2.name)
        val content2 = readEntryContent(zis)
        assertEquals("Second file content", content2)

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun entryMetadata() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(false, entry.isDirectory)
        zis.close()
    }

    @Test
    fun directoryEntries() {
        val zis = ZipInputStream(TestData.directoryZip)

        val dir = zis.nextEntry
        assertNotNull(dir)
        assertEquals("mydir/", dir.name)
        assertTrue(dir.isDirectory)
        readEntryContent(zis) // consume dir entry data

        val file = zis.nextEntry
        assertNotNull(file)
        assertEquals("mydir/file.txt", file.name)
        assertEquals(false, file.isDirectory)
        val content = readEntryContent(zis)
        assertEquals("Inside directory", content)

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun emptyZip() {
        val zis = ZipInputStream(TestData.emptyZip)
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun closeEntrySkipsData() {
        val zis = ZipInputStream(TestData.multiEntryZip)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("file1.txt", entry1.name)
        // Don't read, just close entry
        zis.closeEntry()

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("file2.txt", entry2.name)
        val content = readEntryContent(zis)
        assertEquals("Second file content", content)

        zis.close()
    }

    @Test
    fun binaryContent() {
        val zis = ZipInputStream(TestData.binaryZip)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("binary.dat", entry.name)

        val data = readEntryBytes(zis)
        assertEquals(256, data.size)
        for (i in 0 until 256) {
            assertEquals(i.toByte(), data[i], "Byte at index $i mismatch")
        }

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun singleByteRead() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.nextEntry
        assertNotNull(entry)

        // Read one byte at a time
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = zis.read()
            if (b == -1) break
            bytes.add(b.toByte())
        }
        assertEquals("Hello, World!", bytes.toByteArray().decodeToString())
        zis.close()
    }

    @Test
    fun availableReturnsCorrectValues() {
        val zis = ZipInputStream(TestData.deflatedZip)

        val entry = zis.nextEntry
        assertNotNull(entry)
        // During entry reading, available should be 1 (per Java spec)
        assertEquals(1, zis.available())

        readEntryContent(zis)
        // After reading all entry data, available should be 0
        assertEquals(0, zis.available())

        zis.close()
    }

    // -- nextEntry property --

    @Test
    fun nextEntryProperty() {
        val zis = ZipInputStream(TestData.multiEntryZip)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("file1.txt", entry1.name)
        readEntryContent(zis)

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("file2.txt", entry2.name)
        readEntryContent(zis)

        assertNull(zis.nextEntry)
        zis.close()
    }

    // -- readBytes() extension --

    @Test
    fun readBytesOnEntry() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.nextEntry
        assertNotNull(entry)

        val content = zis.readBytes()
        assertEquals("Hello, World!", content.decodeToString())

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readBytesOnBinaryEntry() {
        val zis = ZipInputStream(TestData.binaryZip)
        val entry = zis.nextEntry
        assertNotNull(entry)

        val data = zis.readBytes()
        assertEquals(256, data.size)
        for (i in 0 until 256) {
            assertEquals(i.toByte(), data[i], "Byte at index $i mismatch")
        }

        zis.close()
    }

    // -- use {} extension --

    @Test
    fun useClosesStream() {
        val result = ZipInputStream(TestData.deflatedZip).use { zis ->
            val entry = zis.nextEntry
            assertNotNull(entry)
            readEntryContent(zis)
        }
        assertEquals("Hello, World!", result)
    }

    @Test
    fun useWithNextEntryAndReadBytes() {
        val entries = ZipInputStream(TestData.multiEntryZip).use { zis ->
            val result = mutableListOf<Pair<String, String>>()
            while (true) {
                val entry = zis.nextEntry ?: break
                result.add(entry.name to zis.readBytes().decodeToString())
            }
            result
        }
        assertEquals(2, entries.size)
        assertEquals("file1.txt", entries[0].first)
        assertEquals("First file content", entries[0].second)
        assertEquals("file2.txt", entries[1].first)
        assertEquals("Second file content", entries[1].second)
    }

    // -- Tests for CLI-generated ZIPs (zip command) --

    @Test
    fun cliStoredZip() {
        val zis = ZipInputStream(TestData.cliStoredZip)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.STORED, entry.method)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun cliDeflatedMultiEntry() {
        val zis = ZipInputStream(TestData.cliDeflatedZip)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("hello.txt", entry1.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("binary.txt", entry2.name)
        assertEquals("Binary test", readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun cliWithDirZip() {
        val zis = ZipInputStream(TestData.cliWithDirZip)

        val dir = zis.nextEntry
        assertNotNull(dir)
        assertEquals("subdir/", dir.name)
        assertTrue(dir.isDirectory)
        readEntryContent(zis)

        val file = zis.nextEntry
        assertNotNull(file)
        assertEquals("subdir/nested.txt", file.name)
        assertEquals("Nested file", readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun cliMixedBinaryZip() {
        val zis = ZipInputStream(TestData.cliMixedZip)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("hello.txt", entry1.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("raw.bin", entry2.name)
        val data = readEntryBytes(zis)
        assertEquals(7, data.size)
        assertEquals(0x00.toByte(), data[0])
        assertEquals(0x01.toByte(), data[1])
        assertEquals(0x02.toByte(), data[2])
        assertEquals(0x03.toByte(), data[3])
        assertEquals(0xFF.toByte(), data[4])
        assertEquals(0xFE.toByte(), data[5])
        assertEquals(0xFD.toByte(), data[6])

        assertNull(zis.nextEntry)
        zis.close()
    }

    // -- Tests for 7zz-generated ZIPs --

    @Test
    fun sevenStoredZip() {
        val zis = ZipInputStream(TestData.sevenStoredZip)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun sevenDeflatedMultiEntry() {
        val zis = ZipInputStream(TestData.sevenDeflatedZip)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("binary.txt", entry1.name)
        assertEquals("Binary test", readEntryContent(zis))

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("hello.txt", entry2.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))

        val entry3 = zis.nextEntry
        assertNotNull(entry3)
        assertEquals("subdir/nested.txt", entry3.name)
        assertEquals("Nested file", readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }

    // -- Invalid/empty data --

    @Test
    fun emptyByteArrayThrows() {
        val zis = ZipInputStream(byteArrayOf())
        assertFailsWith<ZipFormatException> { zis.nextEntry }
        zis.close()
    }

    @Test
    fun invalidDataThrows() {
        val zis = ZipInputStream(byteArrayOf(0x00, 0x01, 0x02))
        assertFailsWith<ZipFormatException> { zis.nextEntry }
        zis.close()
    }

    @Test
    fun truncatedHeaderThrows() {
        // Valid signature but truncated header — should throw, not silently return null
        val zis = ZipInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        assertFailsWith<ZipFormatException> { zis.nextEntry }
        zis.close()
    }

    @Test
    fun truncatedMidSignatureThrowsNotSilentEnd() {
        // An archive cut partway through a header signature must be reported as truncated,
        // not silently accepted as a clean end of archive after the entries read so far.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hello".encodeToByteArray())
            zos.closeEntry()
        }
        val bytes = out.toByteArray()
        // No ZIP comment, so the EOCD is the last 22 bytes; its central-directory offset
        // (LE u32 at +16) is where the reader looks for the next signature after the entry.
        val eocd = bytes.size - 22
        val cdOffset = (bytes[eocd + 16].toInt() and 0xFF) or
            ((bytes[eocd + 17].toInt() and 0xFF) shl 8) or
            ((bytes[eocd + 18].toInt() and 0xFF) shl 16) or
            ((bytes[eocd + 19].toInt() and 0xFF) shl 24)
        val truncated = bytes.copyOf(cdOffset + 2) // two bytes into the CD signature
        assertFailsWith<ZipFormatException> {
            ZipInputStream(truncated).use { zis ->
                zis.nextEntry
                zis.readBytes()
                zis.nextEntry
            }
        }
    }

    private fun abcZip(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for (name in listOf("a", "b", "c")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.encodeToByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun lastIndexOfSig(bytes: ByteArray, b2: Int, b3: Int): Int =
        (bytes.size - 4 downTo 0).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                bytes[it + 2] == b2.toByte() && bytes[it + 3] == b3.toByte()
        }

    private fun entryNames(bytes: ByteArray): List<String> =
        ZipInputStream(bytes).use { zis -> generateSequence { zis.nextEntry?.name }.toList() }

    @Test
    fun corruptLocalHeaderSignatureThrows() {
        val bytes = abcZip()
        bytes[lastIndexOfSig(bytes, 3, 4) + 2] = 9
        assertFailsWith<ZipFormatException> { entryNames(bytes) }
    }

    @Test
    fun truncatedAtEntryBoundaryThrows() {
        val bytes = abcZip()
        val last = lastIndexOfSig(bytes, 3, 4)
        val e = assertFailsWith<ZipFormatException> { entryNames(bytes.copyOf(last)) }
        assertTrue(e.message.orEmpty().startsWith("Truncated ZIP"), e.message)
        // Cut inside the next signature.
        for (extra in 1..3) {
            assertFailsWith<ZipFormatException> { entryNames(bytes.copyOf(last + extra)) }
        }
    }

    @Test
    fun truncatedInsideDataDescriptorThrows() {
        val bytes = abcZip()
        // Entry c's descriptor: the last one, just before the central directory.
        val descriptor = lastIndexOfSig(bytes, 7, 8)
        for (cut in listOf(2, 6, 10)) {
            assertFailsWith<ZipFormatException> { entryNames(bytes.copyOf(descriptor + cut)) }
        }
    }

    @Test
    fun nonPkBlockBeforeCentralDirectoryEndsEntries() {
        // Like an APK Signing Block: no PK signature, starts with its length.
        val block = ByteArray(32) { 0x11 }
        assertEquals(listOf("a", "b", "c"), entryNames(insertBeforeCentralDirectory(abcZip(), block)))
    }

    private fun insertBeforeCentralDirectory(bytes: ByteArray, block: ByteArray): ByteArray {
        val cd = (0..bytes.size - 4).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
        }
        return bytes.copyOf(cd) + block + bytes.copyOfRange(cd, bytes.size)
    }

    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }

    // One ID-value pair (ID 0x7109871a, the v2 signature scheme) filling the block.
    private fun apkSigningBlock(size: Long, magic: String = "APK Sig Block 42"): ByteArray {
        val pairLen = size - 24 - 8
        val pair = le64(pairLen) + byteArrayOf(0x1a, 0x87.toByte(), 0x09, 0x71) + ByteArray((pairLen - 4).toInt())
        return le64(size) + pair + le64(size) + magic.encodeToByteArray()
    }

    @Test
    fun apkSigningBlockWithPkLengthEndsEntries() {
        // A length whose low bytes read as "PK" must not be taken for a bad header.
        val block = apkSigningBlock(0x00034b50)
        assertEquals(listOf("a", "b", "c"), entryNames(insertBeforeCentralDirectory(abcZip(), block)))
    }

    @Test
    fun pkSignatureWithoutApkMagicThrows() {
        val block = apkSigningBlock(0x00034b50, magic = "APK Sig Block 43")
        assertFailsWith<ZipFormatException> { entryNames(insertBeforeCentralDirectory(abcZip(), block)) }
    }

    @Test
    fun pkSignatureWithOversizedLengthThrowsWithoutSkipping() {
        // A length over the cap is rejected up front: the error is the bad signature,
        // not the end of input that skipping to the trailer would hit.
        val block = le64(0x7FFF4b50) + ByteArray(64)
        val e = assertFailsWith<ZipFormatException> { entryNames(insertBeforeCentralDirectory(abcZip(), block)) }
        assertTrue(e.message.orEmpty().startsWith("Bad header signature"), e.message)
    }

    @Test
    fun pkSignatureWithBadFirstPairThrowsWithoutSkipping() {
        val block = le64(0x00034b50) + le64(0x00034b50) + ByteArray(64)
        val e = assertFailsWith<ZipFormatException> { entryNames(insertBeforeCentralDirectory(abcZip(), block)) }
        assertTrue(e.message.orEmpty().startsWith("Bad header signature"), e.message)
    }

    @Test
    fun deflatedCrcEqualToSignatureWithoutDescriptorSignature() {
        // CRC-32 is 0x08074b50, the descriptor signature (#50).
        val content = "hello issue 50, payload ".encodeToByteArray() +
            byteArrayOf(0xb5.toByte(), 0x0e, 0x80.toByte(), 0xa4.toByte())
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("x.bin"))
                zip.write(content)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("y.txt"))
                zip.write("second".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        // The descriptor's signature followed by its CRC: "PK\7\8" twice.
        val pkpk = byteArrayOf(0x50, 0x4b, 7, 8, 0x50, 0x4b, 7, 8)
        val sig = (0..bytes.size - 8).first { i -> pkpk.indices.all { bytes[i + it] == pkpk[it] } }
        val stripped = bytes.copyOf(sig) + bytes.copyOfRange(sig + 4, bytes.size)
        for (data in listOf(bytes, stripped)) {
            ZipInputStream(data).use { zis ->
                assertEquals("x.bin", zis.nextEntry?.name)
                assertContentEquals(content, zis.readBytes())
                assertEquals("y.txt", zis.nextEntry?.name)
                assertEquals("second", zis.readBytes().decodeToString())
            }
        }
    }

    @Test
    fun nextEntryKeepsReturningNullAfterEnd() {
        ZipInputStream(abcZip()).use { zis ->
            repeat(3) { assertNotNull(zis.nextEntry) }
            repeat(3) { assertNull(zis.nextEntry) }
        }
    }

    @Test
    fun unsupportedCompressionMethodThrows() {
        // Patch the first local file header's compression method (offset 8) to an
        // unsupported value (6 = imploded). Valid archive, feature we do not implement.
        val bytes = TestData.multiEntryZip.copyOf()
        bytes[8] = 6
        bytes[9] = 0
        val zis = ZipInputStream(bytes)
        assertFailsWith<ZipUnsupportedFeatureException> { zis.nextEntry }
        zis.close()
    }
}
