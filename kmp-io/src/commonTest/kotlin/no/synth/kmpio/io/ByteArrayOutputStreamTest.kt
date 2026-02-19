package no.synth.kmpio.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class ByteArrayOutputStreamTest {

    @Test
    fun singleByteWrite() {
        val out = ByteArrayOutputStream()
        out.write(0x41)
        out.write(0x42)
        out.write(0x43)
        assertEquals(3, out.size())
        assertEquals("ABC", out.toByteArray().decodeToString())
    }

    @Test
    fun bufferWrite() {
        val out = ByteArrayOutputStream()
        val data = "Hello, World!".encodeToByteArray()
        out.write(data, 0, data.size)
        assertEquals("Hello, World!", out.toByteArray().decodeToString())
    }

    @Test
    fun writeByteArray() {
        val out = ByteArrayOutputStream()
        out.write("Hello".encodeToByteArray())
        assertEquals("Hello", out.toByteArray().decodeToString())
    }

    @Test
    fun size() {
        val out = ByteArrayOutputStream()
        assertEquals(0, out.size())
        out.write(1)
        assertEquals(1, out.size())
        out.write(byteArrayOf(2, 3, 4), 0, 3)
        assertEquals(4, out.size())
    }

    @Test
    fun reset() {
        val out = ByteArrayOutputStream()
        out.write("Hello".encodeToByteArray())
        assertEquals(5, out.size())
        out.reset()
        assertEquals(0, out.size())
        assertEquals(0, out.toByteArray().size)
        out.write("Hi".encodeToByteArray())
        assertEquals(2, out.size())
        assertEquals("Hi", out.toByteArray().decodeToString())
    }

    @Test
    fun emptyStream() {
        val out = ByteArrayOutputStream()
        assertEquals(0, out.size())
        assertContentEquals(byteArrayOf(), out.toByteArray())
    }

    @Test
    fun closeIsNoOp() {
        val out = ByteArrayOutputStream()
        out.write(1)
        out.close()
        assertEquals(1, out.size())
    }

    @Test
    fun bufferGrowth() {
        val out = ByteArrayOutputStream(2)
        out.write(1)
        out.write(2)
        out.write(3) // forces growth
        out.write(4)
        assertEquals(4, out.size())
        assertContentEquals(byteArrayOf(1, 2, 3, 4), out.toByteArray())
    }

    @Test
    fun unsignedByteValues() {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0x80)
        out.write(0x7F)
        val result = out.toByteArray()
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0x80.toByte(), result[1])
        assertEquals(0x7F.toByte(), result[2])
    }

    @Test
    fun writeTo() {
        val src = ByteArrayOutputStream()
        src.write("Hello".encodeToByteArray())
        val dst = ByteArrayOutputStream()
        src.writeTo(dst)
        assertEquals("Hello", dst.toByteArray().decodeToString())
    }

    @Test
    fun partialBufferWrite() {
        val out = ByteArrayOutputStream()
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        out.write(data, 3, 4) // writes bytes 3,4,5,6
        assertEquals(4, out.size())
        assertContentEquals(byteArrayOf(3, 4, 5, 6), out.toByteArray())
    }

    @Test
    fun largeWrite() {
        val out = ByteArrayOutputStream()
        val data = ByteArray(10000) { (it % 256).toByte() }
        out.write(data, 0, data.size)
        assertEquals(10000, out.size())
        assertContentEquals(data, out.toByteArray())
    }

    @Test
    fun toByteArrayReturnsCopy() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 2, 3), 0, 3)
        val arr1 = out.toByteArray()
        val arr2 = out.toByteArray()
        assertContentEquals(arr1, arr2)
        arr1[0] = 99
        // Modifying returned array should not affect the stream
        assertContentEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun useReturnsBlockValue() {
        val result = ByteArrayOutputStream().use { out ->
            out.write(byteArrayOf(1, 2, 3), 0, 3)
            out.toByteArray()
        }
        assertContentEquals(byteArrayOf(1, 2, 3), result)
    }
}
