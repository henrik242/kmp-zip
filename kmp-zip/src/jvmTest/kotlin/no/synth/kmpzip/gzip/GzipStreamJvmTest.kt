package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
        val proc = ProcessBuilder("bash", "-c", "printf '%s' '$original' | gzip").start()
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
}
