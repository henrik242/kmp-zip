package no.synth.kmplibs.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ByteArrayInputStreamTest {

    @Test
    fun singleByteRead() {
        val stream = ByteArrayInputStream(byteArrayOf(0x41, 0x42, 0x43))
        assertEquals(0x41, stream.read())
        assertEquals(0x42, stream.read())
        assertEquals(0x43, stream.read())
        assertEquals(-1, stream.read())
    }

    @Test
    fun bufferRead() {
        val data = "Hello, World!".encodeToByteArray()
        val stream = ByteArrayInputStream(data)
        val buf = ByteArray(5)
        val n = stream.read(buf, 0, 5)
        assertEquals(5, n)
        assertEquals("Hello", buf.decodeToString())
    }

    @Test
    fun available() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val stream = ByteArrayInputStream(data)
        assertEquals(5, stream.available())
        stream.read()
        assertEquals(4, stream.available())
        stream.read(ByteArray(2), 0, 2)
        assertEquals(2, stream.available())
    }

    @Test
    fun skip() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val stream = ByteArrayInputStream(data)
        val skipped = stream.skip(3)
        assertEquals(3L, skipped)
        assertEquals(4, stream.read())
    }

    @Test
    fun markAndReset() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val stream = ByteArrayInputStream(data)
        stream.read() // 1
        stream.mark(0)
        assertEquals(2, stream.read())
        assertEquals(3, stream.read())
        stream.reset()
        assertEquals(2, stream.read())
    }

    @Test
    fun markSupported() {
        val stream = ByteArrayInputStream(byteArrayOf(1))
        assertTrue(stream.markSupported())
    }

    @Test
    fun closeIsNoOp() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        stream.close()
        // After close, reads should still work (Java behavior)
        assertEquals(1, stream.read())
    }

    @Test
    fun emptyStream() {
        val stream = ByteArrayInputStream(byteArrayOf())
        assertEquals(-1, stream.read())
        assertEquals(0, stream.available())
        val buf = ByteArray(10)
        assertEquals(-1, stream.read(buf, 0, 10))
    }

    @Test
    fun offsetConstructor() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val stream = ByteArrayInputStream(data, 3, 4) // bytes 3,4,5,6
        assertEquals(4, stream.available())
        assertEquals(3, stream.read())
        assertEquals(4, stream.read())
        assertEquals(5, stream.read())
        assertEquals(6, stream.read())
        assertEquals(-1, stream.read())
    }

    @Test
    fun readBeyondEnd() {
        val data = byteArrayOf(1, 2)
        val stream = ByteArrayInputStream(data)
        val buf = ByteArray(10)
        val n = stream.read(buf, 0, 10)
        assertEquals(2, n)
        assertEquals(1, buf[0])
        assertEquals(2, buf[1])
        assertEquals(-1, stream.read(buf, 0, 10))
    }

    @Test
    fun unsignedByteValues() {
        // Ensure bytes > 127 are returned as unsigned (0-255)
        val data = byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x7F)
        val stream = ByteArrayInputStream(data)
        assertEquals(255, stream.read())
        assertEquals(128, stream.read())
        assertEquals(127, stream.read())
    }
}
