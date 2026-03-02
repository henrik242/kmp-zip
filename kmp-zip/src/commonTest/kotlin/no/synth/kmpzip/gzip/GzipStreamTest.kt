package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes
import no.synth.kmpzip.zip.TestData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GzipStreamTest {

    private fun gzipCompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val gos = GzipOutputStream(baos)
        gos.write(data)
        gos.close()
        return baos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        val gis = GzipInputStream(ByteArrayInputStream(data))
        val result = gis.readBytes()
        gis.close()
        return result
    }

    @Test
    fun roundTripSimpleString() {
        val original = "Hello, World!".encodeToByteArray()
        val compressed = gzipCompress(original)
        val decompressed = gzipDecompress(compressed)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun roundTripEmptyData() {
        val original = ByteArray(0)
        val compressed = gzipCompress(original)
        val decompressed = gzipDecompress(compressed)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun roundTripBinaryData() {
        val original = ByteArray(256) { it.toByte() }
        val compressed = gzipCompress(original)
        val decompressed = gzipDecompress(compressed)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun roundTripLargeData() {
        val original = ByteArray(100_000) { (it % 256).toByte() }
        val compressed = gzipCompress(original)
        val decompressed = gzipDecompress(compressed)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun compressedSmallerThanOriginalForRepetitiveData() {
        val original = "AAAAAAAAAA".repeat(1000).encodeToByteArray()
        val compressed = gzipCompress(original)
        assertEquals(true, compressed.size < original.size,
            "Expected compressed (${compressed.size}) < original (${original.size})")
    }

    @Test
    fun gzipMagicBytes() {
        val compressed = gzipCompress("test".encodeToByteArray())
        // GZIP magic number: 0x1f 0x8b
        assertEquals(0x1f, compressed[0].toInt() and 0xFF)
        assertEquals(0x8b, compressed[1].toInt() and 0xFF)
    }

    @Test
    fun writeInChunks() {
        val baos = ByteArrayOutputStream()
        val gos = GzipOutputStream(baos)
        gos.write("Hello, ".encodeToByteArray())
        gos.write("World!".encodeToByteArray())
        gos.close()

        val decompressed = gzipDecompress(baos.toByteArray())
        assertEquals("Hello, World!", decompressed.decodeToString())
    }

    @Test
    fun singleByteWrite() {
        val baos = ByteArrayOutputStream()
        val gos = GzipOutputStream(baos)
        for (b in "ABC".encodeToByteArray()) {
            gos.write(b.toInt() and 0xFF)
        }
        gos.close()

        val decompressed = gzipDecompress(baos.toByteArray())
        assertEquals("ABC", decompressed.decodeToString())
    }

    @Test
    fun singleByteRead() {
        val compressed = gzipCompress("Hello".encodeToByteArray())
        val gis = GzipInputStream(ByteArrayInputStream(compressed))
        val result = StringBuilder()
        while (true) {
            val b = gis.read()
            if (b == -1) break
            result.append(b.toChar())
        }
        gis.close()
        assertEquals("Hello", result.toString())
    }

    @Test
    fun finishWithoutClose() {
        val baos = ByteArrayOutputStream()
        val gos = GzipOutputStream(baos)
        gos.write("Test".encodeToByteArray())
        gos.finish()
        // Don't close - just finish. The data should be readable.
        val decompressed = gzipDecompress(baos.toByteArray())
        assertEquals("Test", decompressed.decodeToString())
    }

    @Test
    fun useClosesStream() {
        val baos = ByteArrayOutputStream()
        GzipOutputStream(baos).use { gos ->
            gos.write("Test".encodeToByteArray())
        }

        val decompressed = gzipDecompress(baos.toByteArray())
        assertEquals("Test", decompressed.decodeToString())
    }

    @Test
    fun decompressCliGzip() {
        val decompressed = gzipDecompress(TestData.cliGzip)
        assertEquals("Hello from gzip CLI", decompressed.decodeToString())
    }

    @Test
    fun byteArrayConvenienceFactory() {
        val compressed = gzipCompress("Hello from factory".encodeToByteArray())
        val gis = GzipInputStream(compressed)
        val decompressed = gis.readBytes()
        gis.close()
        assertEquals("Hello from factory", decompressed.decodeToString())
    }
}
