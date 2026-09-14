package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GzipStreamJvmTest {

    @Test
    fun readJavaGzipOutput() {
        // Compress with java.util.zip.GZIPOutputStream
        val jBaos = java.io.ByteArrayOutputStream()
        val jGos = java.util.zip.GZIPOutputStream(jBaos)
        jGos.write("Hello from Java!".encodeToByteArray())
        jGos.close()

        // Decompress with our GzipInputStream
        val gis = GzipInputStream(ByteArrayInputStream(jBaos.toByteArray()))
        val result = gis.readBytes()
        gis.close()
        assertEquals("Hello from Java!", result.decodeToString())
    }

    @Test
    fun javaReadsOurGzipOutput() {
        // Compress with our GzipOutputStream
        val baos = ByteArrayOutputStream()
        val gos = GzipOutputStream(baos)
        gos.write("Hello from KMP!".encodeToByteArray())
        gos.close()

        // Decompress with java.util.zip.GZIPInputStream
        val jGis = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
        val result = jGis.readBytes().decodeToString()
        jGis.close()
        assertEquals("Hello from KMP!", result)
    }

    @Test
    fun gzipCliDecompressesOurOutput() {
        val original = "Hello from KMP gzip"
        val baos = ByteArrayOutputStream()
        GzipOutputStream(baos).use { it.write(original.encodeToByteArray()) }

        val tmp = java.io.File.createTempFile("kmpzip-test", ".gz")
        try {
            tmp.writeBytes(baos.toByteArray())
            val proc = ProcessBuilder("gzip", "-d", "-c", tmp.absolutePath).start()
            val result = proc.inputStream.readBytes().decodeToString()
            proc.waitFor()
            assertEquals(original, result)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun ourGzipInputStreamReadsCliOutput() {
        val original = "Hello from gzip CLI subprocess"
        // Feed gzip's stdin directly instead of via `bash -c` so the test runs on
        // Windows runners (Git-Bash quoting mangles the pipeline command).
        val proc = ProcessBuilder("gzip").start()
        proc.outputStream.use { it.write(original.encodeToByteArray()) }
        val gzipBytes = proc.inputStream.readBytes()
        proc.waitFor()

        val gis = GzipInputStream(ByteArrayInputStream(gzipBytes))
        val result = gis.readBytes().decodeToString()
        gis.close()

        assertEquals(original, result)
    }

    @Test
    fun roundTripLargeBinaryWithJava() {
        val original = ByteArray(50_000) { (it * 7 % 256).toByte() }

        // Compress with ours
        val baos = ByteArrayOutputStream()
        GzipOutputStream(baos).use { it.write(original) }

        // Decompress with Java
        val jResult = java.util.zip.GZIPInputStream(
            java.io.ByteArrayInputStream(baos.toByteArray())
        ).use { it.readBytes() }

        assertContentEquals(original, jResult)
    }

    // -- Error mapping: java.util.zip errors become GzipFormatException, matching the
    //    pure-Kotlin targets, while a genuine source IOException is left untouched. --

    @Test
    fun notInGzipFormatThrowsFormatException() {
        // Constructor path: GZIPInputStream validates the header eagerly and throws
        // java.util.zip.ZipException, which must be mapped, preserving the original as cause.
        val e = assertFailsWith<GzipFormatException> {
            GzipInputStream(ByteArrayInputStream(ByteArray(32) { 0 }))
        }
        assertNotNull(e.cause)
        assertTrue(e.cause is java.util.zip.ZipException)
    }

    @Test
    fun truncatedThrowsFormatException() {
        // read() path: an EOFException from the codec must be mapped, cause preserved.
        val baos = ByteArrayOutputStream()
        GzipOutputStream(baos).use { it.write("hello world ".repeat(10).encodeToByteArray()) }
        val truncated = baos.toByteArray().let { it.copyOf(it.size - 5) }
        val e = assertFailsWith<GzipFormatException> {
            GzipInputStream(ByteArrayInputStream(truncated)).use { it.readBytes() }
        }
        assertNotNull(e.cause)
    }

    @Test
    fun underlyingIoErrorIsNotReclassified() {
        val boom = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("disk gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("disk gone")
        }
        // The constructor reads the header from the source; a raw IOException from the
        // source must propagate as-is, not be reclassified as GzipFormatException.
        val e = assertFailsWith<java.io.IOException> { GzipInputStream(boom) }
        assertTrue(e !is GzipFormatException)
    }

    @Test
    fun sourceEofExceptionIsNotReclassified() {
        // EOFException is one of the types the codec mapping catches, but when the SOURCE
        // throws it (not GZIPInputStream's own truncation), it must propagate unchanged.
        val boom = object : InputStream() {
            override fun read(): Int = throw java.io.EOFException("source eof")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.EOFException("source eof")
        }
        // assertFailsWith<EOFException> already proves it was not mapped to GzipFormatException
        // (which is not an EOFException); the message confirms it is the source's own exception.
        val e = assertFailsWith<java.io.EOFException> { GzipInputStream(boom) }
        assertEquals("source eof", e.message)
    }

    @Test
    fun sourceZipExceptionIsNotReclassified() {
        // Likewise a java.util.zip.ZipException raised by the source, not the codec.
        val boom = object : InputStream() {
            override fun read(): Int = throw java.util.zip.ZipException("source zip")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.util.zip.ZipException("source zip")
        }
        val e = assertFailsWith<java.util.zip.ZipException> { GzipInputStream(boom) }
        assertEquals("source zip", e.message)
    }
}
