package no.synth.kmpio.zip

import no.synth.kmpio.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class ZipOutputStreamTest {

    private fun createZip(block: (ZipOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        block(zos)
        zos.close()
        return baos.toByteArray()
    }

    private fun readEntries(data: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        val zis = ZipInputStream(data)
        while (true) {
            val entry = zis.nextEntry ?: break
            result.add(entry.name to zis.readBytes())
        }
        zis.close()
        return result
    }

    @Test
    fun singleDeflatedEntry() {
        val content = "Hello, World!"
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("hello.txt", entries[0].first)
        assertEquals(content, entries[0].second.decodeToString())
    }

    @Test
    fun multipleDeflatedEntries() {
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("file1.txt"))
            zos.write("First file content".encodeToByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("file2.txt"))
            zos.write("Second file content".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(2, entries.size)
        assertEquals("file1.txt", entries[0].first)
        assertEquals("First file content", entries[0].second.decodeToString())
        assertEquals("file2.txt", entries[1].first)
        assertEquals("Second file content", entries[1].second.decodeToString())
    }

    @Test
    fun emptyZip() {
        val data = createZip { }
        val entries = readEntries(data)
        assertEquals(0, entries.size)
    }

    @Test
    fun emptyEntry() {
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("empty.txt"))
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("empty.txt", entries[0].first)
        assertEquals(0, entries[0].second.size)
    }

    @Test
    fun binaryContent() {
        val binaryData = ByteArray(256) { it.toByte() }
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("binary.dat"))
            zos.write(binaryData)
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("binary.dat", entries[0].first)
        assertContentEquals(binaryData, entries[0].second)
    }

    @Test
    fun directoryEntry() {
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("mydir/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("mydir/file.txt"))
            zos.write("Inside directory".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(2, entries.size)
        assertEquals("mydir/", entries[0].first)
        assertEquals("mydir/file.txt", entries[1].first)
        assertEquals("Inside directory", entries[1].second.decodeToString())
    }

    @Test
    fun largeContent() {
        val largeData = ByteArray(100_000) { (it % 256).toByte() }
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("large.bin"))
            zos.write(largeData)
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertContentEquals(largeData, entries[0].second)
    }

    @Test
    fun writeInChunks() {
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("chunked.txt"))
            zos.write("Hello, ".encodeToByteArray())
            zos.write("World!".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("Hello, World!", entries[0].second.decodeToString())
    }

    @Test
    fun singleByteWrite() {
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("single.txt"))
            for (b in "ABC".encodeToByteArray()) {
                zos.write(b.toInt() and 0xFF)
            }
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("ABC", entries[0].second.decodeToString())
    }

    @Test
    fun autoCloseEntry() {
        // putNextEntry should auto-close previous entry
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("first.txt"))
            zos.write("First".encodeToByteArray())
            // Don't explicitly closeEntry - putNextEntry should handle it
            zos.putNextEntry(ZipEntry("second.txt"))
            zos.write("Second".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(2, entries.size)
        assertEquals("First", entries[0].second.decodeToString())
        assertEquals("Second", entries[1].second.decodeToString())
    }

    @Test
    fun storedEntry() {
        val content = "Hello, Stored!".encodeToByteArray()
        val crc = computeTestCrc(content)
        val data = createZip { zos ->
            val entry = ZipEntry("stored.txt")
            entry.method = ZipConstants.STORED
            entry.size = content.size.toLong()
            entry.compressedSize = content.size.toLong()
            entry.crc = crc
            zos.putNextEntry(entry)
            zos.write(content)
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("stored.txt", entries[0].first)
        assertEquals("Hello, Stored!", entries[0].second.decodeToString())
    }

    @Test
    fun setMethodStored() {
        val content = "Hello, Stored Method!".encodeToByteArray()
        val crc = computeTestCrc(content)
        val data = createZip { zos ->
            zos.setMethod(ZipConstants.STORED)
            val entry = ZipEntry("stored.txt")
            entry.size = content.size.toLong()
            entry.compressedSize = content.size.toLong()
            entry.crc = crc
            zos.putNextEntry(entry)
            zos.write(content)
            zos.closeEntry()
        }

        val entries = readEntries(data)
        assertEquals(1, entries.size)
        assertEquals("Hello, Stored Method!", entries[0].second.decodeToString())
    }

    @Test
    fun finishWithoutClose() {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("test.txt"))
        zos.write("Test".encodeToByteArray())
        zos.closeEntry()
        zos.finish()

        // After finish, the ZIP should be readable
        val entries = readEntries(baos.toByteArray())
        assertEquals(1, entries.size)
        assertEquals("Test", entries[0].second.decodeToString())
    }

    @Test
    fun useClosesStream() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("test.txt"))
            zos.write("Test".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readEntries(baos.toByteArray())
        assertEquals(1, entries.size)
        assertEquals("Test", entries[0].second.decodeToString())
    }

    // Simple CRC32 computation for test use (works cross-platform)
    private fun computeTestCrc(data: ByteArray): Long {
        // Use ZipOutputStream with DEFLATED to compute CRC indirectly,
        // or compute manually. For simplicity, use a lookup table.
        var crc = 0xFFFFFFFFL
        for (b in data) {
            val index = ((crc xor (b.toLong() and 0xFF)) and 0xFF).toInt()
            crc = (crc ushr 8) xor crcTable[index]
        }
        return crc xor 0xFFFFFFFFL
    }

    companion object {
        private val crcTable = LongArray(256) { n ->
            var c = n.toLong()
            for (k in 0 until 8) {
                c = if (c and 1L != 0L) {
                    0xEDB88320L xor (c ushr 1)
                } else {
                    c ushr 1
                }
            }
            c
        }
    }
}
