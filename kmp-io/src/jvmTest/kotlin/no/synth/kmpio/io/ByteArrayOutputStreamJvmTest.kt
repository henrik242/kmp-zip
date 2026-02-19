package no.synth.kmpio.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class ByteArrayOutputStreamJvmTest {

    @Test
    fun identicalSingleByteWrite() {
        val ours = ByteArrayOutputStream()
        val java = java.io.ByteArrayOutputStream()

        for (b in listOf(0, 1, 127, 128, 255, 42)) {
            ours.write(b)
            java.write(b)
        }
        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }

    @Test
    fun identicalBufferWrite() {
        val data = "Hello, World! This is a test of buffer writing.".encodeToByteArray()
        val ours = ByteArrayOutputStream()
        val java = java.io.ByteArrayOutputStream()

        ours.write(data, 0, data.size)
        java.write(data, 0, data.size)

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }

    @Test
    fun identicalPartialWrite() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val ours = ByteArrayOutputStream()
        val java = java.io.ByteArrayOutputStream()

        ours.write(data, 3, 4)
        java.write(data, 3, 4)

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }

    @Test
    fun identicalReset() {
        val ours = ByteArrayOutputStream()
        val java = java.io.ByteArrayOutputStream()

        ours.write("Hello".encodeToByteArray())
        java.write("Hello".encodeToByteArray())
        ours.reset()
        java.reset()

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())

        ours.write("Hi".encodeToByteArray())
        java.write("Hi".encodeToByteArray())

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }

    @Test
    fun identicalGrowth() {
        val ours = ByteArrayOutputStream(2)
        val java = java.io.ByteArrayOutputStream(2)

        val data = ByteArray(1000) { (it % 256).toByte() }
        ours.write(data, 0, data.size)
        java.write(data, 0, data.size)

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }

    @Test
    fun identicalWriteTo() {
        val oursSrc = ByteArrayOutputStream()
        val javaSrc = java.io.ByteArrayOutputStream()
        val data = "Test data".encodeToByteArray()
        oursSrc.write(data, 0, data.size)
        javaSrc.write(data, 0, data.size)

        val oursDst = ByteArrayOutputStream()
        val javaDst = java.io.ByteArrayOutputStream()
        oursSrc.writeTo(oursDst)
        javaSrc.writeTo(javaDst)

        assertContentEquals(javaDst.toByteArray(), oursDst.toByteArray())
    }

    @Test
    fun identicalEmpty() {
        val ours = ByteArrayOutputStream()
        val java = java.io.ByteArrayOutputStream()

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }

    @Test
    fun identicalUnsignedByteValues() {
        val ours = ByteArrayOutputStream()
        val java = java.io.ByteArrayOutputStream()

        for (i in 0 until 256) {
            ours.write(i)
            java.write(i)
        }

        assertEquals(java.size(), ours.size())
        assertContentEquals(java.toByteArray(), ours.toByteArray())
    }
}
