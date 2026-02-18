package no.synth.kmpio.zip

import no.synth.kmpio.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class ZipOutputStreamJvmTest {

    private data class EntryData(val name: String, val content: ByteArray, val method: Int, val isDirectory: Boolean)

    private fun readWithJava(data: ByteArray): List<EntryData> {
        val jis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(data))
        val entries = mutableListOf<EntryData>()
        while (true) {
            val entry = jis.nextEntry ?: break
            val content = jis.readBytes()
            entries.add(EntryData(entry.name, content, entry.method, entry.isDirectory))
        }
        jis.close()
        return entries
    }

    private fun createWithOurs(block: (ZipOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        block(zos)
        zos.close()
        return baos.toByteArray()
    }

    private fun createWithJava(block: (java.util.zip.ZipOutputStream) -> Unit): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val zos = java.util.zip.ZipOutputStream(baos)
        block(zos)
        zos.close()
        return baos.toByteArray()
    }

    @Test
    fun oursReadableByJava() {
        val data = createWithOurs { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("Hello, World!".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readWithJava(data)
        assertEquals(1, entries.size)
        assertEquals("hello.txt", entries[0].name)
        assertEquals("Hello, World!", entries[0].content.decodeToString())
    }

    @Test
    fun oursMultiEntryReadableByJava() {
        val data = createWithOurs { zos ->
            zos.putNextEntry(ZipEntry("file1.txt"))
            zos.write("First file content".encodeToByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("file2.txt"))
            zos.write("Second file content".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readWithJava(data)
        assertEquals(2, entries.size)
        assertEquals("file1.txt", entries[0].name)
        assertEquals("First file content", entries[0].content.decodeToString())
        assertEquals("file2.txt", entries[1].name)
        assertEquals("Second file content", entries[1].content.decodeToString())
    }

    @Test
    fun oursBinaryReadableByJava() {
        val binaryData = ByteArray(256) { it.toByte() }
        val data = createWithOurs { zos ->
            zos.putNextEntry(ZipEntry("binary.dat"))
            zos.write(binaryData)
            zos.closeEntry()
        }

        val entries = readWithJava(data)
        assertEquals(1, entries.size)
        assertContentEquals(binaryData, entries[0].content)
    }

    @Test
    fun oursDirectoryReadableByJava() {
        val data = createWithOurs { zos ->
            zos.putNextEntry(ZipEntry("mydir/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("mydir/file.txt"))
            zos.write("Inside directory".encodeToByteArray())
            zos.closeEntry()
        }

        val entries = readWithJava(data)
        assertEquals(2, entries.size)
        assertEquals("mydir/", entries[0].name)
        assertEquals(true, entries[0].isDirectory)
        assertEquals("mydir/file.txt", entries[1].name)
        assertEquals("Inside directory", entries[1].content.decodeToString())
    }

    @Test
    fun oursLargeContentReadableByJava() {
        val largeData = ByteArray(100_000) { (it % 256).toByte() }
        val data = createWithOurs { zos ->
            zos.putNextEntry(ZipEntry("large.bin"))
            zos.write(largeData)
            zos.closeEntry()
        }

        val entries = readWithJava(data)
        assertEquals(1, entries.size)
        assertContentEquals(largeData, entries[0].content)
    }

    @Test
    fun javaCreatedReadableByOurs() {
        val data = createWithJava { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            zos.write("Hello from Java!".encodeToByteArray())
            zos.closeEntry()
        }

        val zis = ZipInputStream(data)
        val entry = zis.nextEntry
        assertEquals("hello.txt", entry?.name)
        assertEquals("Hello from Java!", zis.readBytes().decodeToString())
        zis.close()
    }

    @Test
    fun roundTripOursToOurs() {
        val data = createWithOurs { zos ->
            zos.putNextEntry(ZipEntry("test.txt"))
            zos.write("Round trip!".encodeToByteArray())
            zos.closeEntry()
        }

        val zis = ZipInputStream(data)
        val entry = zis.nextEntry
        assertEquals("test.txt", entry?.name)
        assertEquals("Round trip!", zis.readBytes().decodeToString())
        zis.close()
    }

    @Test
    fun oursStoredReadableByJava() {
        val content = "Hello, Stored!".encodeToByteArray()
        val crc = java.util.zip.CRC32()
        crc.update(content)

        val data = createWithOurs { zos ->
            val entry = ZipEntry("stored.txt")
            entry.method = ZipConstants.STORED
            entry.size = content.size.toLong()
            entry.compressedSize = content.size.toLong()
            entry.crc = crc.value
            zos.putNextEntry(entry)
            zos.write(content)
            zos.closeEntry()
        }

        val entries = readWithJava(data)
        assertEquals(1, entries.size)
        assertEquals("stored.txt", entries[0].name)
        assertEquals("Hello, Stored!", entries[0].content.decodeToString())
        assertEquals(java.util.zip.ZipEntry.STORED, entries[0].method)
    }

    @Test
    fun oursEmptyZipReadableByJava() {
        val data = createWithOurs { }
        val entries = readWithJava(data)
        assertEquals(0, entries.size)
    }
}
