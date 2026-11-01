package no.synth.kmpzlib

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeflateEdgeCaseTest {
    @Test
    fun rejectsInvalidLevels() {
        assertFailsWith<IllegalArgumentException> { Deflater(10) }
        assertFailsWith<IllegalArgumentException> { Deflater(-2) }
    }

    @Test
    fun callsWithoutInputMakeNoProgress() {
        val d = Deflater(6, ZlibFormat.RAW)
        val out = ByteArray(100)
        repeat(3) {
            val r = d.deflate(EMPTY_INPUT, 0, 0, out, 0, out.size)
            assertEquals(0, r.bytesConsumed)
            assertEquals(0, r.bytesProduced)
            assertFalse(r.streamEnd)
        }
        val end = d.deflate(EMPTY_INPUT, 0, 0, out, 0, out.size, finish = true)
        assertTrue(end.streamEnd)
        assertContentEquals(byteArrayOf(3, 0), out.copyOf(end.bytesProduced))
    }

    @Test
    fun outputDoesNotDependOnChunking() {
        val data = samples().first { it.first == "mixed" }.second.copyOf(40_000)
        // Not level 0: as in zlib, stored block sizes depend on the output room offered.
        for (level in listOf(1, 3, 4, 6, 9)) {
            val expected = compress(data, level, ZlibFormat.ZLIB)
            for ((inChunk, outChunk) in listOf(1 to 1, 7 to 13, 1000 to 3, 32768 to 65536, 40_000 to 1)) {
                assertContentEquals(
                    expected, compress(data, level, ZlibFormat.ZLIB, inChunk, outChunk),
                    "level $level $inChunk/$outChunk",
                )
            }
            assertContentEquals(data, decompress(expected).first, "level $level")
        }
    }

    @Test
    fun storedLevelSizes() {
        val rnd = Random(9)
        for (size in listOf(0, 1, 65535, 65536, 65537, 140_000)) {
            val data = rnd.nextBytes(size)
            for ((inChunk, outChunk) in listOf(65536 to 65536, 200_000 to 200_000, 1000 to 777, 70_000 to 100, 33_000 to 300_000)) {
                for (format in ZlibFormat.entries) {
                    val packed = compress(data, 0, format, inChunk, outChunk)
                    val (unpacked, consumed) = decompress(packed, format, inChunk, outChunk)
                    assertContentEquals(data, unpacked, "size $size $format $inChunk/$outChunk")
                    assertEquals(packed.size, consumed)
                }
            }
            // Stored blocks add 5 bytes per block of at most 65535.
            val raw = compress(data, 0, ZlibFormat.RAW, 200_000, 200_000)
            assertTrue(raw.size <= size + 5 * (size / 65535 + 1), "size $size: ${raw.size}")
        }
    }

    @Test
    fun inputPassedWithFinish() {
        // All input in the finishing call, with room for all output.
        val rnd = Random(10)
        for (size in listOf(0, 1, 100, 20_000, 40_000, 100_000)) {
            val data = rnd.nextBytes(size / 2) + ByteArray(size - size / 2)
            for (level in listOf(0, 1, 6)) {
                for (format in ZlibFormat.entries) {
                    val buf = ByteArray(size + 1000)
                    val r = Deflater(level, format).deflate(data, 0, data.size, buf, 0, buf.size, finish = true)
                    assertTrue(r.streamEnd)
                    assertEquals(data.size, r.bytesConsumed)
                    val packed = buf.copyOf(r.bytesProduced)
                    assertContentEquals(data, decompress(packed, format).first, "size $size level $level $format")
                    if (level > 0) assertContentEquals(compress(data, level, format), packed, "size $size level $level $format")
                }
            }
        }
    }

    @Test
    fun skewedFrequenciesLimitCodeLengths() {
        // Fibonacci symbol counts give an optimal Huffman tree deeper than DEFLATE's
        // 15-bit limit, so the encoder must rebalance it.
        val data = skewedData()
        for (level in listOf(1, 6, 9)) {
            val packed = compress(data, level, ZlibFormat.RAW)
            assertContentEquals(data, decompress(packed, ZlibFormat.RAW).first, "level $level")
        }
    }

    @Test
    fun longRepeatsAndMaximumMatchLength() {
        val unit = Random(11).nextBytes(20_000)
        val data = unit + unit + ByteArray(5000) + unit.copyOf(1000)
        for (level in 1..9) {
            val packed = compress(data, level)
            assertTrue(packed.size < 21_000, "level $level: ${packed.size}")
            assertContentEquals(data, decompress(packed, inChunk = 333, outChunk = 4097).first, "level $level")
        }
    }
}

/** Symbols with Fibonacci counts, shuffled. */
fun skewedData(): ByteArray {
    val counts = IntArray(25)
    counts[0] = 1
    counts[1] = 1
    for (i in 2 until counts.size) counts[i] = counts[i - 1] + counts[i - 2]
    val data = ByteArray(counts.sum())
    var p = 0
    for ((sym, n) in counts.withIndex()) repeat(n) { data[p++] = (sym * 7).toByte() }
    data.shuffle(Random(13))
    return data
}
