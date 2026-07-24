package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.TestData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsGzipTest {

    @Test
    fun recognizesGzipBytes() {
        assertTrue(isGzip(TestData.cliGzip))
        assertTrue(isGzip(byteArrayOf(0x1f, 0x8b.toByte())))
    }

    @Test
    fun recognizesOwnOutput() {
        val baos = ByteArrayOutputStream()
        GzipOutputStream(baos).apply {
            write("Hello, World!".encodeToByteArray())
            close()
        }
        assertTrue(isGzip(baos.toByteArray()))
    }

    @Test
    fun rejectsNonGzipBytes() {
        assertFalse(isGzip(ByteArray(0)))
        assertFalse(isGzip(byteArrayOf(0x1f)))
        assertFalse(isGzip(byteArrayOf(0x8b.toByte(), 0x1f)))
        assertFalse(isGzip("plain text".encodeToByteArray()))
        assertFalse(isGzip(TestData.storedZip))
    }
}
