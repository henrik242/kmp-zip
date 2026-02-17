package no.synth.kmplibs.zip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipInputStreamTest {

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
        val entry = zis.getNextEntry()
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.STORED, entry.method)

        val content = readEntryContent(zis)
        assertEquals("Hello, World!", content)

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun singleDeflatedEntry() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.getNextEntry()
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.DEFLATED, entry.method)

        val content = readEntryContent(zis)
        assertEquals("Hello, World!", content)

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun multipleEntries() {
        val zis = ZipInputStream(TestData.multiEntryZip)

        val entry1 = zis.getNextEntry()
        assertNotNull(entry1)
        assertEquals("file1.txt", entry1.name)
        val content1 = readEntryContent(zis)
        assertEquals("First file content", content1)

        val entry2 = zis.getNextEntry()
        assertNotNull(entry2)
        assertEquals("file2.txt", entry2.name)
        val content2 = readEntryContent(zis)
        assertEquals("Second file content", content2)

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun entryMetadata() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.getNextEntry()
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(false, entry.isDirectory)
        zis.close()
    }

    @Test
    fun directoryEntries() {
        val zis = ZipInputStream(TestData.directoryZip)

        val dir = zis.getNextEntry()
        assertNotNull(dir)
        assertEquals("mydir/", dir.name)
        assertTrue(dir.isDirectory)
        readEntryContent(zis) // consume dir entry data

        val file = zis.getNextEntry()
        assertNotNull(file)
        assertEquals("mydir/file.txt", file.name)
        assertEquals(false, file.isDirectory)
        val content = readEntryContent(zis)
        assertEquals("Inside directory", content)

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun emptyZip() {
        val zis = ZipInputStream(TestData.emptyZip)
        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun closeEntrySkipsData() {
        val zis = ZipInputStream(TestData.multiEntryZip)

        val entry1 = zis.getNextEntry()
        assertNotNull(entry1)
        assertEquals("file1.txt", entry1.name)
        // Don't read, just close entry
        zis.closeEntry()

        val entry2 = zis.getNextEntry()
        assertNotNull(entry2)
        assertEquals("file2.txt", entry2.name)
        val content = readEntryContent(zis)
        assertEquals("Second file content", content)

        zis.close()
    }

    @Test
    fun binaryContent() {
        val zis = ZipInputStream(TestData.binaryZip)
        val entry = zis.getNextEntry()
        assertNotNull(entry)
        assertEquals("binary.dat", entry.name)

        val data = readEntryBytes(zis)
        assertEquals(256, data.size)
        for (i in 0 until 256) {
            assertEquals(i.toByte(), data[i], "Byte at index $i mismatch")
        }

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun singleByteRead() {
        val zis = ZipInputStream(TestData.deflatedZip)
        val entry = zis.getNextEntry()
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

        val entry = zis.getNextEntry()
        assertNotNull(entry)
        // During entry reading, available should be 1 (per Java spec)
        assertEquals(1, zis.available())

        readEntryContent(zis)
        // After reading all entry data, available should be 0
        assertEquals(0, zis.available())

        zis.close()
    }

    // -- Tests for CLI-generated ZIPs (zip command) --

    @Test
    fun cliStoredZip() {
        val zis = ZipInputStream(TestData.cliStoredZip)
        val entry = zis.getNextEntry()
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.STORED, entry.method)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))
        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun cliDeflatedMultiEntry() {
        val zis = ZipInputStream(TestData.cliDeflatedZip)

        val entry1 = zis.getNextEntry()
        assertNotNull(entry1)
        assertEquals("hello.txt", entry1.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))

        val entry2 = zis.getNextEntry()
        assertNotNull(entry2)
        assertEquals("binary.txt", entry2.name)
        assertEquals("Binary test", readEntryContent(zis))

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun cliWithDirZip() {
        val zis = ZipInputStream(TestData.cliWithDirZip)

        val dir = zis.getNextEntry()
        assertNotNull(dir)
        assertEquals("subdir/", dir.name)
        assertTrue(dir.isDirectory)
        readEntryContent(zis)

        val file = zis.getNextEntry()
        assertNotNull(file)
        assertEquals("subdir/nested.txt", file.name)
        assertEquals("Nested file", readEntryContent(zis))

        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun cliMixedBinaryZip() {
        val zis = ZipInputStream(TestData.cliMixedZip)

        val entry1 = zis.getNextEntry()
        assertNotNull(entry1)
        assertEquals("hello.txt", entry1.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))

        val entry2 = zis.getNextEntry()
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

        assertNull(zis.getNextEntry())
        zis.close()
    }

    // -- Tests for 7zz-generated ZIPs --

    @Test
    fun sevenStoredZip() {
        val zis = ZipInputStream(TestData.sevenStoredZip)
        val entry = zis.getNextEntry()
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))
        assertNull(zis.getNextEntry())
        zis.close()
    }

    @Test
    fun sevenDeflatedMultiEntry() {
        val zis = ZipInputStream(TestData.sevenDeflatedZip)

        val entry1 = zis.getNextEntry()
        assertNotNull(entry1)
        assertEquals("binary.txt", entry1.name)
        assertEquals("Binary test", readEntryContent(zis))

        val entry2 = zis.getNextEntry()
        assertNotNull(entry2)
        assertEquals("hello.txt", entry2.name)
        assertEquals("Hello from zip CLI\\!", readEntryContent(zis))

        val entry3 = zis.getNextEntry()
        assertNotNull(entry3)
        assertEquals("subdir/nested.txt", entry3.name)
        assertEquals("Nested file", readEntryContent(zis))

        assertNull(zis.getNextEntry())
        zis.close()
    }
}
