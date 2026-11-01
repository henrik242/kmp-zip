package no.synth.kmpzlib

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater as JInflater
import java.util.zip.Deflater as JDeflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun checksumsMatchJava() {
        val rnd = Random(77)
        val data = rnd.nextBytes(200_000)
        repeat(300) {
            val off = rnd.nextInt(0, 64)
            val len = if (it < 100) rnd.nextInt(0, 40) else rnd.nextInt(0, data.size - off)
            val crc = CRC32().apply { update(data, off, len) }.value.toInt()
            val adler = Adler32().apply { update(data, off, len) }.value.toInt()
            assertEquals(crc, crc32(0, data, len, off), "crc off $off len $len")
            assertEquals(adler, adler32(1, data, len, off), "adler off $off len $len")
        }
    }

    @Test
    fun weInflateJavaFlushModes() {
        // Sync and full flushes put empty stored blocks mid-stream; full flushes also
        // reset the match window.
        val data = samples().first { it.first == "text" }.second
        for (mode in listOf(JDeflater.SYNC_FLUSH, JDeflater.FULL_FLUSH)) {
            for (nowrap in listOf(true, false)) {
                val d = JDeflater(6, nowrap)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(70_000)
                var pos = 0
                while (pos < data.size) {
                    val n = minOf(10_007, data.size - pos)
                    d.setInput(data, pos, n)
                    pos += n
                    do {
                        val k = d.deflate(buf, 0, buf.size, mode)
                        out.write(buf, 0, k)
                    } while (k == buf.size)
                }
                d.finish()
                while (!d.finished()) out.write(buf, 0, d.deflate(buf))
                d.end()
                val format = if (nowrap) ZlibFormat.RAW else ZlibFormat.ZLIB
                for ((inChunk, outChunk) in listOf(65536 to 65536, 5 to 3, 1 to 1000)) {
                    assertContentEquals(
                        data, decompress(out.toByteArray(), format, inChunk, outChunk).first,
                        "mode $mode nowrap $nowrap $inChunk/$outChunk",
                    )
                }
            }
        }
    }

    @Test
    fun javaPresetDictionaryIsRejected() {
        val d = JDeflater()
        d.setDictionary("dictionary".encodeToByteArray())
        d.setInput("dictionary words".encodeToByteArray())
        d.finish()
        val buf = ByteArray(100)
        val packed = buf.copyOf(d.deflate(buf))
        d.end()
        val e = assertFailsWith<ZlibDataException> { decompress(packed) }
        assertEquals("preset dictionary not supported", e.message)
    }

    @Test
    fun javaInflatesEdgeCases() {
        val unit = Random(5).nextBytes(40_000)
        val inputs = listOf(
            "skewed" to skewedData(),
            "repeats" to unit + unit + ByteArray(70_000),
            "stored" to Random(6).nextBytes(140_000),
        )
        for ((name, data) in inputs) {
            for (level in listOf(0, 1, 6, 9)) {
                for (nowrap in listOf(true, false)) {
                    val format = if (nowrap) ZlibFormat.RAW else ZlibFormat.ZLIB
                    val packed = compress(data, level, format, inChunk = 9999, outChunk = 4321)
                    assertContentEquals(data, javaInflate(packed, nowrap), "$name level $level nowrap $nowrap")
                }
            }
        }
    }

    @Test
    fun randomGzipRoundTrips() {
        val rnd = Random(4321)
        repeat(100) { i ->
            val data = ByteArray(rnd.nextInt(0, 100_000)).also { a ->
                // Runs of random length and value mixed with noise.
                var p = 0
                while (p < a.size) {
                    val n = minOf(a.size - p, rnd.nextInt(1, 300))
                    if (rnd.nextBoolean()) a.fill(rnd.nextInt(256).toByte(), p, p + n)
                    else for (j in p until p + n) a[j] = rnd.nextInt(256).toByte()
                    p += n
                }
            }
            val level = rnd.nextInt(-1, 10)
            val ours = compress(data, level, ZlibFormat.GZIP, rnd.nextInt(1, 30_000), rnd.nextInt(1, 30_000))
            assertContentEquals(data, GZIPInputStream(ByteArrayInputStream(ours)).readBytes(), "case $i")
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            val theirs = decompress(bos.toByteArray(), ZlibFormat.GZIP, rnd.nextInt(1, 30_000), rnd.nextInt(1, 30_000))
            assertContentEquals(data, theirs.first, "case $i")
            assertEquals(bos.size(), theirs.second)
        }
    }

    @Test
    fun weInflateConcatenatedJavaGzipMembers() {
        val parts = listOf("first".encodeToByteArray(), ByteArray(0), Random(8).nextBytes(50_000))
        val bos = ByteArrayOutputStream()
        for (p in parts) GZIPOutputStream(bos).apply { write(p); finish() }
        val all = bos.toByteArray()
        val inf = Inflater(ZlibFormat.GZIP)
        var pos = 0
        for (p in parts) {
            val out = ByteArray(p.size + 10)
            val r = inf.inflate(all, pos, all.size - pos, out, 0, out.size)
            assertTrue(r.streamEnd)
            assertContentEquals(p, out.copyOf(r.bytesProduced))
            pos += r.bytesConsumed
            inf.reset()
        }
        assertEquals(all.size, pos)
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
