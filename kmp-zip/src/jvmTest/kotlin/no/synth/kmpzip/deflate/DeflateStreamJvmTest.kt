package no.synth.kmpzip.deflate

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DeflateStreamJvmTest {

    private val sample = ByteArray(100_000) { i -> (i % 251 xor (i ushr 9)).toByte() }

    @Test
    fun javaReadsOurOutput() {
        for (format in DeflateFormat.entries) {
            val baos = ByteArrayOutputStream()
            DeflateOutputStream(baos, format).use { it.write(sample) }
            val inflater = Inflater(format == DeflateFormat.RAW)
            // Raw inflate in java.util.zip needs one byte past the end of the data.
            val input = baos.toByteArray() + if (format == DeflateFormat.RAW) byteArrayOf(0) else byteArrayOf()
            val out = InflaterInputStream(input.inputStream(), inflater).use { it.readBytes() }
            inflater.end()
            assertContentEquals(sample, out, "$format")
        }
    }

    @Test
    fun weReadJavaOutput() {
        for (format in DeflateFormat.entries) {
            val baos = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_COMPRESSION, format == DeflateFormat.RAW)
            DeflaterOutputStream(baos, deflater).use { it.write(sample) }
            deflater.end()
            assertContentEquals(sample, InflateInputStream(baos.toByteArray(), format).use { it.readBytes() }, "$format")
        }
    }
}
