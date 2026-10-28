package no.synth.kmpzip.deflate

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.NoProgressException
import no.synth.kmpzip.io.StallingInputStream
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeflateStreamTest {

    // Python: zlib.compress(b"hello, zlib")
    private val zlibHello = bytes(120, -100, -53, 72, -51, -55, -55, -41, 81, -88, -54, -55, 76, 2, 0, 24, -78, 4, 18)

    // Python: zlib.compressobj(wbits=-15), b"hello, raw"
    private val rawHello = bytes(-53, 72, -51, -55, -55, -41, 81, 40, 74, 44, 7, 0)

    // Python: zlib.compressobj(zdict=b"hello"), b"hello hello"
    private val zlibWithDictionary = bytes(120, -69, 6, 44, 2, 21, -53, 0, 17, 10, 96, 18, 0, 25, -111, 4, 73)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun deflate(data: ByteArray, format: DeflateFormat, level: Int = -1): ByteArray {
        val baos = ByteArrayOutputStream()
        DeflateOutputStream(baos, format, level).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun inflate(data: ByteArray, format: DeflateFormat): ByteArray =
        InflateInputStream(data, format).use { it.readBytes() }

    // Compressible, but not trivially, and larger than the internal buffers.
    private val sample = ByteArray(200_000) { i -> ((i * 31) xor (i ushr 7)).toByte() }

    @Test
    fun readsZlibFromPython() {
        assertEquals("hello, zlib", inflate(zlibHello, DeflateFormat.ZLIB).decodeToString())
    }

    @Test
    fun readsRawFromPython() {
        assertEquals("hello, raw", inflate(rawHello, DeflateFormat.RAW).decodeToString())
    }

    @Test
    fun zlibIsTheDefault() {
        assertEquals("hello, zlib", InflateInputStream(zlibHello).use { it.readBytes() }.decodeToString())
        val baos = ByteArrayOutputStream()
        DeflateOutputStream(baos).use { it.write("x".encodeToByteArray()) }
        assertEquals(0x78, baos.toByteArray()[0].toInt())
    }

    @Test
    fun roundTripsBothFormatsAtEveryLevel() {
        for (format in DeflateFormat.entries) {
            for (level in -1..9) {
                assertContentEquals(sample, inflate(deflate(sample, format, level), format), "$format level $level")
            }
        }
    }

    @Test
    fun roundTripsEmptyInput() {
        for (format in DeflateFormat.entries) {
            assertContentEquals(ByteArray(0), inflate(deflate(ByteArray(0), format), format))
        }
    }

    @Test
    fun zlibOutputHasHeaderAndAdler32Trailer() {
        val data = "Wikipedia".encodeToByteArray()
        val out = deflate(data, DeflateFormat.ZLIB)
        assertEquals(0x78, out[0].toInt())
        assertEquals(0, ((out[0].toInt() and 0xFF) * 256 + (out[1].toInt() and 0xFF)) % 31)
        // Adler-32 of "Wikipedia" is 0x11E60398, stored big-endian.
        assertContentEquals(bytes(0x11, 0xE6 - 256, 0x03, 0x98 - 256), out.copyOfRange(out.size - 4, out.size))
    }

    @Test
    fun rawOutputMatchesZlibWithoutWrapper() {
        val zlib = deflate(sample, DeflateFormat.ZLIB, 6)
        val raw = deflate(sample, DeflateFormat.RAW, 6)
        assertContentEquals(zlib.copyOfRange(2, zlib.size - 4), raw)
    }

    @Test
    fun formatMismatchRejected() {
        assertFailsWith<DeflateFormatException> { inflate(rawHello, DeflateFormat.ZLIB) }
    }

    @Test
    fun corruptChecksumRejected() {
        val bad = zlibHello.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
        assertFailsWith<DeflateFormatException> { inflate(bad, DeflateFormat.ZLIB) }
    }

    @Test
    fun truncatedStreamRejected() {
        for ((data, format) in listOf(zlibHello to DeflateFormat.ZLIB, rawHello to DeflateFormat.RAW)) {
            assertFailsWith<DeflateFormatException> { inflate(data.copyOf(data.size - 3), format) }
        }
    }

    @Test
    fun emptyInputRejected() {
        for (format in DeflateFormat.entries) {
            assertFailsWith<DeflateFormatException> { inflate(ByteArray(0), format) }
        }
    }

    @Test
    fun presetDictionaryRejected() {
        assertFailsWith<DeflateFormatException> { inflate(zlibWithDictionary, DeflateFormat.ZLIB) }
    }

    @Test
    fun stopsAtEndOfCompressedData() {
        val data = zlibHello + "trailing".encodeToByteArray()
        InflateInputStream(data).use { iis ->
            assertEquals("hello, zlib", iis.readBytes().decodeToString())
            assertEquals(-1, iis.read())
            assertEquals(0, iis.available())
        }
    }

    @Test
    fun readsOneByteAtATime() {
        val out = ByteArrayOutputStream()
        InflateInputStream(deflate(sample, DeflateFormat.RAW), DeflateFormat.RAW).use { iis ->
            while (true) {
                val b = iis.read()
                if (b == -1) break
                out.write(b)
            }
        }
        assertContentEquals(sample, out.toByteArray())
    }

    @Test
    fun stallingSourceFailsInsteadOfSpinning() {
        val data = deflate(sample, DeflateFormat.ZLIB)
        assertFailsWith<NoProgressException> {
            InflateInputStream(StallingInputStream(data, data.size / 2)).use { it.readBytes() }
        }
    }

    @Test
    fun outOfRangeReadRejected() {
        for ((off, len) in listOf(0 to 8192, -1 to 1, 0 to -1, 2 to 0)) {
            InflateInputStream(zlibHello).use { iis ->
                assertFailsWith<IndexOutOfBoundsException>("off=$off len=$len") { iis.read(ByteArray(1), off, len) }
            }
        }
    }

    @Test
    fun outOfRangeWriteRejected() {
        for ((off, len) in listOf(0 to 8192, -1 to 1, 0 to -1, 2 to 0)) {
            DeflateOutputStream(ByteArrayOutputStream()).use { dos ->
                assertFailsWith<IndexOutOfBoundsException>("off=$off len=$len") { dos.write(ByteArray(1), off, len) }
            }
        }
    }

    @Test
    fun zeroLengthReadAtEndReturnsZero() {
        InflateInputStream(zlibHello).use { iis ->
            iis.readBytes()
            assertEquals(0, iis.read(ByteArray(4), 0, 0))
            assertEquals(-1, iis.read(ByteArray(4), 0, 4))
        }
    }

        @Test
    fun invalidLevelRejected() {
        assertFailsWith<IllegalArgumentException> { DeflateOutputStream(ByteArrayOutputStream(), level = 10) }
        assertFailsWith<IllegalArgumentException> { DeflateOutputStream(ByteArrayOutputStream(), level = -2) }
    }

    @Test
    fun finishLeavesUnderlyingStreamOpen() {
        var closed = false
        val baos = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        val dos = DeflateOutputStream(baos)
        dos.write("abc".encodeToByteArray())
        dos.finish()
        assertTrue(!closed)
        assertFailsWith<IllegalStateException> { dos.write(1) }
        dos.close()
        assertTrue(closed)
        assertEquals("abc", inflate(baos.toByteArray(), DeflateFormat.ZLIB).decodeToString())
    }

    @Test
    fun closeClosesUnderlyingInput() {
        var closed = false
        val input = object : ByteArrayInputStream(zlibHello) {
            override fun close() {
                closed = true
            }
        }
        InflateInputStream(input).close()
        assertTrue(closed)
    }
}
