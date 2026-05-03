package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes
import no.synth.kmpzip.zip.TestData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

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
    fun roundTripIncompressibleDataAcrossInputBufferBoundary() {
        // Incompressible data forces the compressed stream to be much larger than
        // GzipInputStream's 8192-byte input buffer, so the buffer is refilled many
        // times. Eventually inflate() is called with inputOffset == inputBufLen
        // (the empty-slice case) — this previously crashed because addressOf(size)
        // throws on Kotlin/Native.
        var seed = 0x12345678
        val original = ByteArray(200_000) {
            seed = seed * 1103515245 + 12345
            (seed ushr 16).toByte()
        }
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
    fun decompressConcatenatedMembers() {
        // RFC 1952 §2.2: concatenated gzip members must decode as one logical stream.
        val a = "Hello, ".encodeToByteArray()
        val b = "world!".encodeToByteArray()
        val concatenated = gzipCompress(a) + gzipCompress(b)
        val decompressed = gzipDecompress(concatenated)
        assertEquals("Hello, world!", decompressed.decodeToString())
    }

    @Test
    fun truncatedGzipThrows() {
        val original = ByteArray(50_000) { (it % 256).toByte() }
        val compressed = gzipCompress(original)
        // Drop the last 100 bytes — payload + trailer are now incomplete.
        val truncated = compressed.copyOf(compressed.size - 100)
        assertFails { gzipDecompress(truncated) }
    }

    @Test
    fun corruptedCrc32Throws() {
        val original = "abcdefghij".repeat(1000).encodeToByteArray()
        val compressed = gzipCompress(original)
        // The CRC32 sits at bytes [size-8..size-5). Flip a bit in it.
        val corrupted = compressed.copyOf()
        corrupted[corrupted.size - 8] = (corrupted[corrupted.size - 8].toInt() xor 0x01).toByte()
        assertFails { gzipDecompress(corrupted) }
    }

    @Test
    fun corruptedISizeThrows() {
        val original = "abcdefghij".repeat(1000).encodeToByteArray()
        val compressed = gzipCompress(original)
        // The ISIZE (uncompressed length mod 2^32) sits in the last 4 bytes.
        val corrupted = compressed.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0x01).toByte()
        assertFails { gzipDecompress(corrupted) }
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
