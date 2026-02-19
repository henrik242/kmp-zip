package no.synth.kmpio.kotlinx

import kotlinx.io.Buffer
import no.synth.kmpio.zip.ZipConstants
import no.synth.kmpio.zip.ZipEntry
import no.synth.kmpio.zip.ZipInputStream
import no.synth.kmpio.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZipAdapterTest {

    @Test
    fun roundTripSingleEntry() {
        val buffer = Buffer()

        // Write a ZIP via Sink adapter
        val zos = ZipOutputStream(buffer as kotlinx.io.Sink)
        zos.putNextEntry(ZipEntry("hello.txt"))
        zos.write("Hello, kotlinx-io!".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        // Read it back via Source adapter
        val zis = ZipInputStream(buffer as kotlinx.io.Source)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals("Hello, kotlinx-io!", zis.readBytes().decodeToString())
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
        val zos = ZipOutputStream(buffer as kotlinx.io.Sink)
        for ((name, content) in files) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }
        zos.close()

        // Read
        val zis = ZipInputStream(buffer as kotlinx.io.Source)
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

        val zos = ZipOutputStream(buffer as kotlinx.io.Sink)
        zos.setMethod(ZipConstants.DEFLATED)
        zos.putNextEntry(ZipEntry("deflated.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(buffer as kotlinx.io.Source)
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

        val zos = ZipOutputStream(buffer as kotlinx.io.Sink)
        zos.close()

        val zis = ZipInputStream(buffer as kotlinx.io.Source)
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

        val bytes = ByteArray(3)
        buffer.readAtMostTo(bytes)
        assertEquals(0, bytes[0])
        assertEquals(127, bytes[1])
        assertEquals(-1, bytes[2]) // 255 as signed byte
    }
}
