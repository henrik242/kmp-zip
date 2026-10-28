package no.synth.kmpzip.zip

import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StreamingEncryptionJvmTest {

    private val password = "secret"

    /** Deterministic, incompressible bytes: a xorshift generator. */
    private fun fill(buf: ByteArray, state: LongArray) {
        var x = state[0]
        for (i in buf.indices) {
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            buf[i] = x.toByte()
        }
        state[0] = x
    }

    /**
     * Writes an encrypted entry larger than the 256 MiB the reader buffers when it has to
     * scan for a data descriptor, through a pipe, so neither side can hold it in memory.
     */
    @Test
    fun encryptedEntryLargerThanTheScanLimitStreams() {
        val total = 300L shl 20
        for (encryption in ZipEncryption.entries) {
            val pipeIn = PipedInputStream(1 shl 16)
            val pipeOut = PipedOutputStream(pipeIn)
            var writeError: Throwable? = null
            val writer = thread {
                try {
                    ZipOutputStream(pipeOut, password.encodeToByteArray(), encryption).use { zos ->
                        zos.setLevel(0)
                        zos.putNextEntry(ZipEntry("big.bin"))
                        val buf = ByteArray(1 shl 16)
                        val state = longArrayOf(42)
                        var left = total
                        while (left > 0) {
                            fill(buf, state)
                            val n = minOf(left, buf.size.toLong()).toInt()
                            zos.write(buf, 0, n)
                            left -= n
                        }
                        zos.putNextEntry(ZipEntry("after.txt"))
                        zos.write("after".encodeToByteArray())
                    }
                } catch (e: Throwable) {
                    writeError = e
                    pipeOut.close()
                }
            }

            ZipInputStream(pipeIn, password).use { zis ->
                assertEquals("big.bin", assertNotNull(zis.nextEntry).name)
                val expected = ByteArray(1 shl 16)
                val actual = ByteArray(1 shl 16)
                val state = longArrayOf(42)
                var read = 0L
                while (read < total) {
                    fill(expected, state)
                    val want = minOf(total - read, expected.size.toLong()).toInt()
                    var got = 0
                    while (got < want) {
                        val n = zis.read(actual, got, want - got)
                        check(n > 0) { "$encryption: entry ended after ${read + got} bytes" }
                        got += n
                    }
                    check(expected.copyOf(want).contentEquals(actual.copyOf(want))) { "$encryption: data differs near $read" }
                    read += want
                }
                assertEquals(-1, zis.read(), "$encryption")
                assertEquals("after.txt", assertNotNull(zis.nextEntry).name)
                assertEquals("after", zis.readBytes().decodeToString())
                assertNull(zis.nextEntry)
            }
            writer.join()
            writeError?.let { throw it }
        }
    }

    // DEFLATED only: a STORED entry written without a size has a data descriptor, and a
    // streaming reader cannot tell where its data ends (zip4j's reads it as empty).
    @Test
    fun zip4jReadsStreamedEntries() {
        val content = "streamed for zip4j\n".repeat(500).encodeToByteArray()
        for (encryption in ZipEncryption.entries) {
            val baos = java.io.ByteArrayOutputStream()
            ZipOutputStream(baos, password.encodeToByteArray(), encryption).use { zos ->
                zos.putNextEntry(ZipEntry("a.txt"))
                zos.write(content)
                zos.putNextEntry(ZipEntry("b.txt"))
                zos.write("b".encodeToByteArray())
            }
            Zip4jInputStream(baos.toByteArray().inputStream(), password.toCharArray()).use { zis ->
                assertEquals("a.txt", assertNotNull(zis.nextEntry).fileName, "$encryption")
                assertEquals(content.decodeToString(), zis.readBytes().decodeToString(), "$encryption")
                assertEquals("b.txt", assertNotNull(zis.nextEntry).fileName, "$encryption")
                assertEquals("b", zis.readBytes().decodeToString(), "$encryption")
            }
        }
    }
}
