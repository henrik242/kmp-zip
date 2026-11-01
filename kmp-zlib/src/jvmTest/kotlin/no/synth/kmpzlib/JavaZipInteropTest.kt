package no.synth.kmpzlib

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater as JInflater
import java.util.zip.Deflater as JDeflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class JavaZipInteropTest {
    @Test
    fun javaInflatesOurs() {
        for ((name, data) in samples()) {
            for (level in 0..9) {
                for (nowrap in listOf(true, false)) {
                    val format = if (nowrap) ZlibFormat.RAW else ZlibFormat.ZLIB
                    val packed = compress(data, level, format)
                    assertContentEquals(data, javaInflate(packed, nowrap), "$name level $level nowrap $nowrap")
                }
            }
            val gz = compress(data, format = ZlibFormat.GZIP)
            assertContentEquals(data, GZIPInputStream(ByteArrayInputStream(gz)).readBytes(), "$name gzip")
        }
    }

    @Test
    fun weInflateJavas() {
        for ((name, data) in samples()) {
            for (level in 0..9) {
                for (strategy in listOf(JDeflater.DEFAULT_STRATEGY, JDeflater.FILTERED, JDeflater.HUFFMAN_ONLY)) {
                    for (nowrap in listOf(true, false)) {
                        val format = if (nowrap) ZlibFormat.RAW else ZlibFormat.ZLIB
                        val packed = javaDeflate(data, level, strategy, nowrap)
                        assertContentEquals(
                            data, decompress(packed, format, inChunk = 997, outChunk = 1009).first,
                            "$name level $level strategy $strategy nowrap $nowrap",
                        )
                    }
                }
            }
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            assertContentEquals(data, decompress(bos.toByteArray(), ZlibFormat.GZIP).first, "$name gzip")
        }
    }

    @Test
    fun randomInputsAndChunking() {
        val rnd = Random(1234)
        repeat(200) { i ->
            val size = rnd.nextInt(0, 70_000)
            val alphabet = rnd.nextInt(1, 257)
            val data = ByteArray(size) { rnd.nextInt(alphabet).toByte() }
            val level = rnd.nextInt(-1, 10)
            val inChunk = rnd.nextInt(1, 20_000)
            val outChunk = rnd.nextInt(1, 20_000)
            val packed = compress(data, level, ZlibFormat.ZLIB, inChunk = inChunk, outChunk = outChunk)
            assertContentEquals(data, javaInflate(packed, nowrap = false), "case $i")
            val theirs = javaDeflate(data, if (level < 0) 6 else level, JDeflater.DEFAULT_STRATEGY, nowrap = false)
            assertContentEquals(data, decompress(theirs, ZlibFormat.ZLIB, inChunk, outChunk).first, "case $i")
        }
    }

    private fun javaInflate(data: ByteArray, nowrap: Boolean): ByteArray {
        // java.util.zip needs one extra byte after a raw stream to see its end.
        val input = if (nowrap) data + 0 else data
        val inf = JInflater(nowrap)
        inf.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && !inf.finished() && (inf.needsInput() || inf.needsDictionary())) error("truncated")
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    private fun javaDeflate(data: ByteArray, level: Int, strategy: Int, nowrap: Boolean): ByteArray {
        val d = JDeflater(level, nowrap)
        d.setStrategy(strategy)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
