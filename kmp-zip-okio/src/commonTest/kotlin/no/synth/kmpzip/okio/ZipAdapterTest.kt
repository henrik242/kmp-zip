package no.synth.kmpzip.okio

import okio.Buffer
import no.synth.kmpzip.zip.ZipConstants
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZipAdapterTest {

    @Test
    fun roundTripSingleEntry() {
        val buffer = Buffer()

        // Write a ZIP via BufferedSink adapter
        val zos = ZipOutputStream(buffer as okio.BufferedSink)
        zos.putNextEntry(ZipEntry("hello.txt"))
        zos.write("Hello, okio!".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        // Read it back via BufferedSource adapter
        val zis = ZipInputStream(buffer as okio.BufferedSource)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals("Hello, okio!", zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripMultipleEntries() {
        val buffer = Buffer()

        val files = mapOf(
            "file1.txt" to "First file content",
            "file2.txt" to "Second file content",
            "dir/file3.txt" to "Nested file content",
        )

        // Write
        val zos = ZipOutputStream(buffer as okio.BufferedSink)
        for ((name, content) in files) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }
        zos.close()

        // Read
        val zis = ZipInputStream(buffer as okio.BufferedSource)
        val result = mutableMapOf<String, String>()
        while (true) {
            val entry = zis.nextEntry ?: break
            result[entry.name] = zis.readBytes().decodeToString()
        }
        zis.close()

        assertEquals(files, result)
    }

    @Test
    fun roundTripDeflatedEntry() {
        val buffer = Buffer()

        val content = "Deflated content, explicitly compressed"

        val zos = ZipOutputStream(buffer as okio.BufferedSink)
        zos.setMethod(ZipConstants.DEFLATED)
        zos.putNextEntry(ZipEntry("deflated.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(buffer as okio.BufferedSource)
        val readEntry = zis.nextEntry
        assertNotNull(readEntry)
        assertEquals("deflated.txt", readEntry.name)
        assertEquals(content, zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripEmptyZip() {
        val buffer = Buffer()

        val zos = ZipOutputStream(buffer as okio.BufferedSink)
        zos.close()

        val zis = ZipInputStream(buffer as okio.BufferedSource)
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun sourceInputStreamReadsBytesCorrectly() {
        val buffer = Buffer()
        buffer.write(byteArrayOf(0, 127, -128, -1, 42))

        val input = SourceInputStream(buffer)
        assertEquals(0, input.read())
        assertEquals(127, input.read())
        assertEquals(128, input.read()) // -128 as unsigned
        assertEquals(255, input.read()) // -1 as unsigned
        assertEquals(42, input.read())
        assertEquals(-1, input.read()) // EOF
    }

    @Test
    fun sinkOutputStreamWritesBytesCorrectly() {
        val buffer = Buffer()
        val output = SinkOutputStream(buffer)

        output.write(0)
        output.write(127)
        output.write(255)
        output.flush()

        val bytes = buffer.readByteArray(3)
        assertEquals(0, bytes[0])
        assertEquals(127, bytes[1])
        assertEquals(-1, bytes[2]) // 255 as signed byte
    }
}
