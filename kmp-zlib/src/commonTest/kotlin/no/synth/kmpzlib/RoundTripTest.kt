package no.synth.kmpzlib

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoundTripTest {
    @Test
    fun allLevelsAndFormats() {
        for ((name, data) in samples()) {
            for (format in ZlibFormat.entries) {
                for (level in -1..9) {
                    val packed = compress(data, level, format)
                    val (unpacked, consumed) = decompress(packed, format)
                    assertContentEquals(data, unpacked, "$name $format level $level")
                    assertEquals(packed.size, consumed, "$name $format level $level consumed")
                }
            }
        }
    }

    @Test
    fun tinyBuffers() {
        for ((name, data) in samples().filter { it.second.size < 160_000 }) {
            for ((inChunk, outChunk) in listOf(1 to 1, 1 to 7, 13 to 1, 3 to 300)) {
                val packed = compress(data, 6, ZlibFormat.GZIP, inChunk = inChunk, outChunk = outChunk)
                assertContentEquals(compress(data, 6, ZlibFormat.GZIP), packed, "$name deflate $inChunk/$outChunk")
                val (unpacked, _) = decompress(packed, ZlibFormat.GZIP, inChunk, outChunk)
                assertContentEquals(data, unpacked, "$name inflate $inChunk/$outChunk")
            }
        }
    }

    @Test
    fun leavesTrailingBytesUnconsumed() {
        val data = "trailing data after the stream".encodeToByteArray()
        for (format in ZlibFormat.entries) {
            val packed = compress(data, format = format)
            val withTail = packed + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val (unpacked, consumed) = decompress(withTail, format)
            assertContentEquals(data, unpacked)
            assertEquals(packed.size, consumed, "$format")
        }
    }

    @Test
    fun resetDecodesConcatenatedGzipMembers() {
        val a = "member one".encodeToByteArray()
        val b = "member two".encodeToByteArray()
        val joined = compress(a, format = ZlibFormat.GZIP) + compress(b, format = ZlibFormat.GZIP)
        val inf = Inflater(ZlibFormat.GZIP)
        val out = ByteArray(100)
        val r1 = inf.inflate(joined, 0, joined.size, out, 0, out.size)
        assertTrue(r1.streamEnd)
        assertContentEquals(a, out.copyOf(r1.bytesProduced))
        inf.reset()
        val r2 = inf.inflate(joined, r1.bytesConsumed, joined.size - r1.bytesConsumed, out, 0, out.size)
        assertTrue(r2.streamEnd)
        assertContentEquals(b, out.copyOf(r2.bytesProduced))
    }

    @Test
    fun deflaterResetStartsANewStream() {
        val data = samples().first { it.first == "text" }.second
        val d = Deflater(format = ZlibFormat.ZLIB)
        val out = ByteArray(200_000)
        val first = d.deflate(data, 0, data.size, out, 0, out.size, finish = true)
        assertTrue(first.streamEnd)
        d.reset()
        val second = d.deflate(data, 0, data.size, out, 0, out.size, finish = true)
        assertTrue(second.streamEnd)
        assertContentEquals(compress(data), out.copyOf(second.bytesProduced))
    }

    @Test
    fun corruptDataThrows() {
        val packed = compress(samples().first { it.first == "text" }.second)
        // Flip a bit in the zlib header, the body, and the Adler-32 trailer.
        for (index in listOf(1, packed.size / 2, packed.size - 1)) {
            val bad = packed.copyOf()
            bad[index] = (bad[index].toInt() xor 0x10).toByte()
            assertFailsWith<ZlibDataException>("index $index") { decompress(bad) }
        }
    }

    @Test
    fun corruptGzipTrailerThrows() {
        val packed = compress("gzip".encodeToByteArray(), format = ZlibFormat.GZIP)
        for (index in listOf(packed.size - 8, packed.size - 1)) {
            val bad = packed.copyOf()
            bad[index] = (bad[index].toInt() xor 1).toByte()
            assertFailsWith<ZlibDataException>("index $index") { decompress(bad, ZlibFormat.GZIP) }
        }
    }

    @Test
    fun errorIsSticky() {
        val inf = Inflater(ZlibFormat.ZLIB)
        val junk = byteArrayOf(0x12, 0x34, 0x56)
        val out = ByteArray(10)
        assertFailsWith<ZlibDataException> { inf.inflate(junk, 0, junk.size, out, 0, out.size) }
        assertFailsWith<ZlibDataException> { inf.inflate(junk, 0, 0, out, 0, out.size) }
    }

    @Test
    fun presetDictionaryIsRejected() {
        // zlib header with FDICT set, followed by a dictionary id.
        val header = byteArrayOf(0x78, 0xbb.toByte(), 0, 0, 0, 1)
        assertFailsWith<ZlibDataException> { decompress(header) }
    }

    @Test
    fun randomGarbageNeverCrashes() {
        val rnd = kotlin.random.Random(7)
        val valid = compress(samples().first { it.first == "mixed" }.second, format = ZlibFormat.RAW)
        repeat(300) {
            val bad = valid.copyOf()
            repeat(rnd.nextInt(1, 6)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            try {
                decompress(bad, ZlibFormat.RAW, inChunk = rnd.nextInt(1, 5000), outChunk = rnd.nextInt(1, 5000))
            } catch (_: ZlibDataException) {
            } catch (e: IllegalStateException) {
                // Truncation: decompress() reports a stall when input runs out.
                assertTrue(e.message.orEmpty().startsWith("no progress"), e.toString())
            }
        }
    }

    @Test
    fun finishedDeflaterRejectsMoreInput() {
        val d = Deflater()
        val out = ByteArray(100)
        assertTrue(d.deflate(EMPTY_INPUT, 0, 0, out, 0, out.size, finish = true).streamEnd)
        assertTrue(d.deflate(EMPTY_INPUT, 0, 0, out, 0, out.size, finish = true).streamEnd)
        assertFailsWith<IllegalStateException> { d.deflate(byteArrayOf(1), 0, 1, out, 0, out.size) }
    }

    @Test
    fun finishingDeflaterRejectsMoreInput() {
        val data = samples().first { it.first == "random" }.second
        val d = Deflater()
        val out = ByteArray(16)
        // A small output buffer leaves the stream mid-finish.
        val r = d.deflate(data, 0, data.size, out, 0, out.size, finish = true)
        assertTrue(!r.streamEnd)
        assertFailsWith<IllegalStateException> { d.deflate(data, 0, 1, out, 0, out.size, finish = true) }
        assertFailsWith<IllegalStateException> { d.deflate(EMPTY_INPUT, 0, 0, out, 0, out.size) }
    }

    @Test
    fun zlibHeaderMatchesZlib() {
        // CMF 0x78 (deflate, 32K window); FLG level bits 0 for levels 0-1, 1 for 2-5,
        // 2 for 6 (and the default), 3 for 7-9.
        val expected = mapOf(-1 to 0x9c, 0 to 0x01, 1 to 0x01, 2 to 0x5e, 5 to 0x5e, 6 to 0x9c, 7 to 0xda, 9 to 0xda)
        for ((level, flg) in expected) {
            val packed = compress(byteArrayOf(1, 2, 3), level)
            assertEquals(0x78, packed[0].toInt() and 0xff, "level $level CMF")
            assertEquals(flg, packed[1].toInt() and 0xff, "level $level FLG")
        }
    }

    @Test
    fun matchesPakoOutput() {
        // Produced by pako 3.0.1's deflate() for the same input and options.
        val input = "The quick brown fox jumps over the lazy dog. The quick brown fox jumps over the lazy dog."
            .encodeToByteArray()
        val body = "0bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228c94855c849acaa5448c94fd7530821413100"
        assertEquals(body, compress(input, 1, ZlibFormat.RAW).toHex())
        assertEquals("789c" + body + "aed1202f", compress(input, 6).toHex())
        assertEquals("78da" + body + "aed1202f", compress(input, 9).toHex())
        assertEquals("1f8b0800000000000003" + body + "a70ae99a59000000", compress(input, 6, ZlibFormat.GZIP).toHex())
        assertEquals("015900a6ff" + input.toHex(), compress(input, 0, ZlibFormat.RAW).toHex())
    }

    @Test
    fun matchesPakoOutputOnLargerInput() {
        val words = listOf("lorem", "ipsum", "dolor", "sit", "amet", "zip", "kotlin", "deflate", "x", "consectetur")
        val sb = StringBuilder()
        var s = 12345
        while (sb.length < 100_000) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            sb.append(words[s % words.size]).append(' ')
        }
        val input = sb.toString().encodeToByteArray()
        assertEquals(100_007, input.size)
        // level to (size, CRC-32) of pako 3.0.1's raw deflate() output.
        val expected = mapOf(1 to (15885 to 0xdfc42605), 4 to (11967 to 0xdbc7fc16), 6 to (9842 to 0x9e535129), 9 to (9190 to 0x96fa1131))
        for ((level, sizeAndCrc) in expected) {
            val packed = compress(input, level, ZlibFormat.RAW, inChunk = 1000, outChunk = 777)
            assertEquals(sizeAndCrc.first, packed.size, "level $level size")
            assertEquals(sizeAndCrc.second.toInt(), crc32(0, packed, packed.size, 0), "level $level crc")
        }
    }

    @Test
    fun gzipOptionalHeaderFields() {
        val data = "payload".encodeToByteArray()
        val body = compress(data, format = ZlibFormat.RAW)
        val trailer = ByteArray(8).also { t ->
            val crc = crc32(0, data, data.size, 0)
            for (i in 0..3) t[i] = (crc ushr (8 * i)).toByte()
            t[4] = data.size.toByte()
        }
        // FHCRC | FEXTRA | FNAME | FCOMMENT
        val header = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0x1e, 0, 0, 0, 0, 0, 3) +
            byteArrayOf(4, 0, 'a'.code.toByte(), 'b'.code.toByte(), 2, 0) +
            "name.txt".encodeToByteArray() + 0 +
            "a comment".encodeToByteArray() + 0
        val hcrc = crc32(0, header, header.size, 0)
        val good = header + byteArrayOf(hcrc.toByte(), (hcrc ushr 8).toByte()) + body + trailer
        for (chunk in listOf(1, 2, 3, 7, 1000)) {
            val (out, consumed) = decompress(good, ZlibFormat.GZIP, inChunk = chunk)
            assertContentEquals(data, out, "chunk $chunk")
            assertEquals(good.size, consumed)
        }
        val badHcrc = good.copyOf().also { it[header.size] = (it[header.size].toInt() xor 1).toByte() }
        assertFailsWith<ZlibDataException> { decompress(badHcrc, ZlibFormat.GZIP) }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
