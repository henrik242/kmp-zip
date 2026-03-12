package no.synth.kmpzip.kotlinx

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString
import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipConstants
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun outputStreamSinkWritesBytesCorrectly() {
        val backing = ByteArrayOutputStream()
        val sink = OutputStreamSink(backing)

        val source = Buffer()
        source.write(byteArrayOf(0, 127, -1, 42))
        sink.write(source, 4)
        sink.flush()

        assertContentEquals(byteArrayOf(0, 127, -1, 42), backing.toByteArray())
    }

    @Test
    fun outputStreamSinkChunksLargeWrites() {
        val backing = ByteArrayOutputStream()
        val sink = OutputStreamSink(backing)

        // Write more than the 8192-byte chunk size
        val data = ByteArray(20000) { (it % 256).toByte() }
        val source = Buffer()
        source.write(data)
        sink.write(source, data.size.toLong())
        sink.flush()

        assertContentEquals(data, backing.toByteArray())
    }

    @Test
    fun inputStreamSourceReadsBytesCorrectly() {
        val backing = ByteArrayInputStream(byteArrayOf(0, 127, -128, -1, 42))
        val source = InputStreamSource(backing)

        val sink = Buffer()
        assertEquals(5, source.readAtMostTo(sink, 8192))
        val bytes = ByteArray(5)
        sink.readAtMostTo(bytes)
        assertContentEquals(byteArrayOf(0, 127, -128, -1, 42), bytes)
    }

    @Test
    fun inputStreamSourceReturnsMinusOneAtEof() {
        val backing = ByteArrayInputStream(byteArrayOf())
        val source = InputStreamSource(backing)

        val sink = Buffer()
        assertEquals(-1, source.readAtMostTo(sink, 8192))
    }

    @Test
    fun writeEntryViaOutputStreamSink() {
        val zipBuffer = Buffer()

        val zos = ZipOutputStream(zipBuffer as kotlinx.io.Sink)
        zos.putNextEntry(ZipEntry("streamed.txt"))
        val entrySink = zos.asSink().buffered()
        entrySink.writeString("Streamed via Sink!")
        entrySink.flush()
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(zipBuffer as kotlinx.io.Source)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("streamed.txt", entry.name)
        assertEquals("Streamed via Sink!", zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun writeDeflatedEntryViaOutputStreamSink() {
        val zipBuffer = Buffer()

        val zos = ZipOutputStream(zipBuffer as kotlinx.io.Sink)
        zos.setMethod(ZipConstants.DEFLATED)
        zos.putNextEntry(ZipEntry("deflated.txt"))
        val entrySink = zos.asSink().buffered()
        entrySink.writeString("Deflated content streamed via Sink!")
        entrySink.flush()
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(zipBuffer as kotlinx.io.Source)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("deflated.txt", entry.name)
        assertEquals("Deflated content streamed via Sink!", zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readEntryViaInputStreamSource() {
        val zipBuffer = Buffer()

        val zos = ZipOutputStream(zipBuffer as kotlinx.io.Sink)
        zos.putNextEntry(ZipEntry("readable.txt"))
        zos.write("Read via Source!".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(zipBuffer as kotlinx.io.Source)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("readable.txt", entry.name)
        val entrySource = zis.asSource().buffered()
        assertEquals("Read via Source!", entrySource.readString())
        assertNull(zis.nextEntry)
        zis.close()
    }
}
