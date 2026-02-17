package no.synth.kmplibs.io

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteArrayInputStreamJvmTest {

    private fun readAll(stream: InputStream): ByteArray {
        val buf = ByteArray(4096)
        val out = mutableListOf<Byte>()
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n == -1) break
            for (i in 0 until n) out.add(buf[i])
        }
        return out.toByteArray()
    }

    @Test
    fun identicalSingleByteRead() {
        val data = byteArrayOf(0, 1, 127, -128, -1, 42)
        val ours = ByteArrayInputStream(data)
        val java = java.io.ByteArrayInputStream(data)

        for (i in data.indices) {
            assertEquals(java.read(), ours.read(), "Mismatch at byte $i")
        }
        assertEquals(java.read(), ours.read(), "Mismatch at EOF")
    }

    @Test
    fun identicalBufferRead() {
        val data = "Hello, World! This is a test of buffer reading.".encodeToByteArray()
        val ours = ByteArrayInputStream(data)
        val java = java.io.ByteArrayInputStream(data)

        val ourBuf = ByteArray(10)
        val javaBuf = ByteArray(10)

        while (true) {
            val jn = java.read(javaBuf, 0, 10)
            val on = ours.read(ourBuf, 0, 10)
            assertEquals(jn, on, "Read count mismatch")
            if (jn == -1) break
            for (i in 0 until jn) {
                assertEquals(javaBuf[i], ourBuf[i], "Buffer content mismatch at index $i")
            }
        }
    }

    @Test
    fun identicalAvailable() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val ours = ByteArrayInputStream(data)
        val java = java.io.ByteArrayInputStream(data)

        assertEquals(java.available(), ours.available())
        java.read(); ours.read()
        assertEquals(java.available(), ours.available())
        java.read(ByteArray(2)); ours.read(ByteArray(2))
        assertEquals(java.available(), ours.available())
    }

    @Test
    fun identicalSkip() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val ours = ByteArrayInputStream(data)
        val java = java.io.ByteArrayInputStream(data)

        assertEquals(java.skip(3), ours.skip(3))
        assertEquals(java.read(), ours.read())
        assertEquals(java.skip(100), ours.skip(100))
        assertEquals(java.read(), ours.read())
    }

    @Test
    fun identicalMarkReset() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val ours = ByteArrayInputStream(data)
        val java = java.io.ByteArrayInputStream(data)

        java.read(); ours.read()
        java.mark(0); ours.mark(0)
        assertEquals(java.read(), ours.read())
        assertEquals(java.read(), ours.read())
        java.reset(); ours.reset()
        assertEquals(java.read(), ours.read())
    }

    @Test
    fun identicalOffsetConstructor() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val ours = ByteArrayInputStream(data, 3, 4)
        val java = java.io.ByteArrayInputStream(data, 3, 4)

        assertEquals(java.available(), ours.available())
        while (true) {
            val jb = java.read()
            val ob = ours.read()
            assertEquals(jb, ob)
            if (jb == -1) break
        }
    }

    @Test
    fun identicalEmptyStream() {
        val ours = ByteArrayInputStream(byteArrayOf())
        val java = java.io.ByteArrayInputStream(byteArrayOf())

        assertEquals(java.read(), ours.read())
        assertEquals(java.available(), ours.available())
    }

    @Test
    fun identicalUnsignedByteValues() {
        // All byte values 0-255
        val data = ByteArray(256) { it.toByte() }
        val ours = ByteArrayInputStream(data)
        val java = java.io.ByteArrayInputStream(data)

        for (i in 0 until 256) {
            assertEquals(java.read(), ours.read(), "Mismatch at byte value $i")
        }
        assertEquals(java.read(), ours.read())
    }
}
