package no.synth.kmpzlib

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InflateEdgeCaseTest {
    // Input/output chunk sizes: one call (inflateFast), byte at a time (the slow path),
    // and odd sizes that split matches and refills.
    private val chunkings = listOf(65536 to 65536, 1 to 1, 3 to 7, 997 to 259, 65536 to 1, 1 to 65536)

    private fun assertInflates(stream: ByteArray, expected: ByteArray, format: ZlibFormat = ZlibFormat.RAW) {
        for ((inChunk, outChunk) in chunkings) {
            val (out, consumed) = decompress(stream, format, inChunk, outChunk)
            assertContentEquals(expected, out, "$format $inChunk/$outChunk")
            assertEquals(stream.size, consumed, "$format $inChunk/$outChunk consumed")
        }
    }

    /** Fails in one call, with padding so inflateFast runs, and byte by byte. */
    private fun assertInflateFails(stream: ByteArray, message: String, format: ZlibFormat = ZlibFormat.RAW) {
        val padded = stream + ByteArray(16)
        for ((inChunk, outChunk) in listOf(65536 to 65536, 1 to 1, 2 to 300)) {
            val e = assertFailsWith<ZlibDataException>("$inChunk/$outChunk") {
                decompress(padded, format, inChunk, outChunk)
            }
            assertEquals(message, e.message, "$inChunk/$outChunk")
        }
    }

    private fun randomBytes(n: Int, seed: Int = n) = Random(seed).nextBytes(n)

    @Test
    fun emptyStreams() {
        val fixedEmpty = DeflateWriter().header(true, 1).fixedEnd().toByteArray()
        assertContentEquals(byteArrayOf(3, 0), fixedEmpty)
        assertInflates(fixedEmpty, ByteArray(0))
        assertInflates(DeflateWriter().stored(ByteArray(0), last = true).toByteArray(), ByteArray(0))
        // zlib's own encoding of empty input.
        assertInflates(byteArrayOf(0x78, 0x9c.toByte(), 3, 0, 0, 0, 0, 1), ByteArray(0), ZlibFormat.ZLIB)
        assertInflates(gzipWrap(fixedEmpty, ByteArray(0)), ByteArray(0), ZlibFormat.GZIP)
    }

    @Test
    fun singleByte() {
        val w = DeflateWriter().header(true, 1).fixedLiteral(0xff).fixedEnd()
        val raw = w.toByteArray()
        assertInflates(raw, byteArrayOf(-1))
        assertInflates(zlibWrap(raw, w.expected), w.expected, ZlibFormat.ZLIB)
        assertInflates(gzipWrap(raw, w.expected), w.expected, ZlibFormat.GZIP)
    }

    @Test
    fun storedBlocks() {
        val w = DeflateWriter()
            .stored("hello".encodeToByteArray(), last = false)
            .stored(ByteArray(0), last = false)
            .stored(randomBytes(65535), last = false)
            .stored(randomBytes(1000), last = true)
        assertContentEquals(byteArrayOf(0, 5, 0, 0xfa.toByte(), 0xff.toByte()), w.toByteArray().copyOf(5))
        assertInflates(w.toByteArray(), w.expected)
    }

    @Test
    fun mixedBlockTypes() {
        val dynamic = compress(samples().first { it.first == "text" }.second.copyOf(20_000), 6, ZlibFormat.RAW)
        // Block type bits 01 (fixed) and 10 (dynamic) in the first header.
        assertEquals(2, compress("abc".encodeToByteArray(), 6, ZlibFormat.RAW)[0].toInt() and 6)
        assertEquals(4, dynamic[0].toInt() and 6)

        val w = DeflateWriter()
            .stored("stored ".encodeToByteArray(), last = false)
            .header(false, 1).fixedLiterals("fixed ".encodeToByteArray()).fixedMatch(6, 6).fixedEnd()
            .stored(ByteArray(0), last = false)
            .header(true, 1).fixedLiterals("end".encodeToByteArray()).fixedMatch(10, 22).fixedEnd()
        assertEquals("stored fixed fixed endstored fix", w.expected.decodeToString())
        assertInflates(w.toByteArray(), w.expected)
        assertInflates(zlibWrap(w.toByteArray(), w.expected), w.expected, ZlibFormat.ZLIB)
    }

    @Test
    fun matchLengthsAndOverlappingDistances() {
        // Every length band against distances below, at and above the bulk copy
        // threshold, overlapping (dist < len) and not.
        val w = DeflateWriter().header(true, 1)
        w.fixedLiterals(randomBytes(300))
        val lengths = listOf(3, 4, 5, 10, 11, 18, 31, 32, 33, 34, 63, 64, 65, 100, 130, 200, 227, 257, 258)
        val distances = listOf(1, 2, 3, 4, 5, 7, 8, 15, 16, 17, 19, 31, 32, 33, 63, 64, 100, 257, 258, 300)
        var i = 0
        for (dist in distances) for (len in lengths) {
            w.fixedMatch(len, dist)
            w.fixedLiteral(i++ and 0xff)
        }
        w.fixedEnd()
        assertInflates(w.toByteArray(), w.expected)
        assertInflates(zlibWrap(w.toByteArray(), w.expected), w.expected, ZlibFormat.ZLIB)
    }

    @Test
    fun runOfLength258AtDistanceOne() {
        val w = DeflateWriter().header(true, 1).fixedLiteral('x'.code)
        repeat(1000) { w.fixedMatch(258, 1) }
        w.fixedEnd()
        assertEquals(258_001, w.expected.size)
        assertTrue(w.expected.all { it == 'x'.code.toByte() })
        assertInflates(w.toByteArray(), w.expected)
    }

    @Test
    fun maximumDistance() {
        // A real deflater never reaches back more than 32506 bytes; this goes the full 32 KiB.
        val head = randomBytes(32768)
        val w = DeflateWriter().stored(head, last = false)
            .header(true, 1)
            .fixedMatch(258, 32768)
            .fixedLiteral(1)
            .fixedMatch(3, 32768)
            .fixedMatch(100, 32768)
            .fixedMatch(258, 32767)
            .fixedEnd()
        val expected = w.expected
        assertContentEquals(head.copyOf(258), expected.copyOfRange(32768, 32768 + 258))
        assertInflates(w.toByteArray(), expected)
        assertInflates(zlibWrap(w.toByteArray(), expected), expected, ZlibFormat.ZLIB)
        assertInflates(gzipWrap(w.toByteArray(), expected), expected, ZlibFormat.GZIP)
    }

    @Test
    fun maximumDistanceAcrossManyCalls() {
        // Window wraparound: matches 32 KiB back after far more than 32 KiB of output.
        val w = DeflateWriter().header(true, 1)
        w.fixedLiterals(randomBytes(40_000))
        repeat(400) { i ->
            w.fixedMatch(3 + i % 256, 32768 - i)
            w.fixedLiteral(i and 0xff)
        }
        w.fixedEnd()
        for ((inChunk, outChunk) in listOf(65536 to 65536, 4096 to 1000, 1 to 32767, 1 to 32768, 7 to 33000)) {
            assertContentEquals(w.expected, decompress(w.toByteArray(), ZlibFormat.RAW, inChunk, outChunk).first)
        }
    }

    @Test
    fun distanceTooFarBack() {
        val atStart = DeflateWriter().header(true, 1).fixedMatch(3, 1).fixedEnd()
        assertInflateFails(atStart.toByteArray(), "invalid distance too far back")

        val oneTooFar = DeflateWriter().header(true, 1).fixedLiterals(randomBytes(10)).fixedMatch(5, 11).fixedEnd()
        assertInflateFails(oneTooFar.toByteArray(), "invalid distance too far back")

        // Too far into the window, after earlier calls have filled part of it.
        val w = DeflateWriter().header(true, 1).fixedLiterals(randomBytes(1000)).fixedMatch(5, 1001).fixedEnd()
        val e = assertFailsWith<ZlibDataException> { decompress(w.toByteArray() + ByteArray(16), ZlibFormat.RAW, 65536, 300) }
        assertEquals("invalid distance too far back", e.message)
    }

    @Test
    fun invalidFixedCodes() {
        assertInflateFails(DeflateWriter().header(true, 1).fixedSymbol(286).toByteArray(), "invalid literal/length code")
        assertInflateFails(DeflateWriter().header(true, 1).fixedSymbol(287).toByteArray(), "invalid literal/length code")
        val badDist = DeflateWriter().header(true, 1).fixedLiteral(1).fixedSymbol(257).code(30, 5)
        assertInflateFails(badDist.toByteArray(), "invalid distance code")
        val badDist31 = DeflateWriter().header(true, 1).fixedLiteral(1).fixedSymbol(260).code(31, 5)
        assertInflateFails(badDist31.toByteArray(), "invalid distance code")
    }

    @Test
    fun invalidBlockHeaders() {
        assertInflateFails(byteArrayOf(0x07), "invalid block type")
        assertInflateFails(byteArrayOf(0x01, 5, 0, 0, 0) + "hello".encodeToByteArray(), "invalid stored block lengths")
    }

    @Test
    fun validHandWrittenDynamicBlocks() {
        // Literal 0 and end-of-block only, with no distance codes at all.
        val noDistances = DeflateWriter()
        val zerosAndOnes = mapOf(18 to 1, 0 to 2, 1 to 2)
        dynamicHeader(noDistances, 257, 1, zerosAndOnes)
        val cl = codes(zerosAndOnes)
        emit(noDistances, cl, 1)
        emitZeros(noDistances, cl, 255)
        emit(noDistances, cl, 1)
        emitZeros(noDistances, cl, 1)
        // Literal 0 is code 0, end-of-block code 1.
        repeat(5) { noDistances.code(0, 1) }
        noDistances.code(1, 1)
        assertInflates(noDistances.toByteArray(), ByteArray(5))

        // A single one-bit distance code (incomplete, which is allowed for one code).
        val single = DeflateWriter()
        val lengthCodes = mapOf(18 to 1, 1 to 2, 2 to 2)
        dynamicHeader(single, 258, 1, lengthCodes)
        val c = codes(lengthCodes)
        emitZeros(single, c, 97)
        emit(single, c, 1) // 'a': length 1
        emitZeros(single, c, 158)
        emit(single, c, 2) // end-of-block: length 2
        emit(single, c, 2) // length 3 (symbol 257): length 2
        emit(single, c, 1) // distance 1: length 1
        // 'a' = 0, end-of-block = 10, symbol 257 = 11, distance code 0 = 0.
        single.code(0, 1).code(3, 2).code(0, 1).code(2, 2)
        assertInflates(single.toByteArray(), "aaaa".encodeToByteArray())
    }

    @Test
    fun invalidDynamicHeaders() {
        val tooManyLengths = DeflateWriter().header(true, 2).bits(30, 5).bits(0, 5).bits(0, 4)
        assertInflateFails(tooManyLengths.toByteArray(), "too many length or distance symbols")
        val tooManyDistances = DeflateWriter().header(true, 2).bits(0, 5).bits(30, 5).bits(0, 4)
        assertInflateFails(tooManyDistances.toByteArray(), "too many length or distance symbols")

        val oversubscribed = DeflateWriter()
        dynamicHeader(oversubscribed, 257, 1, mapOf(16 to 1, 17 to 1, 18 to 1))
        assertInflateFails(oversubscribed.toByteArray(), "invalid code lengths set")

        val incomplete = DeflateWriter()
        dynamicHeader(incomplete, 257, 1, mapOf(0 to 1))
        assertInflateFails(incomplete.toByteArray(), "invalid code lengths set")

        val zerosAndRepeat = mapOf(0 to 1, 16 to 1)
        val repeatFirst = DeflateWriter()
        dynamicHeader(repeatFirst, 257, 1, zerosAndRepeat)
        emit(repeatFirst, codes(zerosAndRepeat), 16).bits(0, 2)
        assertInflateFails(repeatFirst.toByteArray(), "invalid bit length repeat")

        val zerosOnly = mapOf(0 to 1, 18 to 1)
        val overflow = DeflateWriter()
        dynamicHeader(overflow, 257, 1, zerosOnly)
        emitZeros(overflow, codes(zerosOnly), 138)
        emitZeros(overflow, codes(zerosOnly), 138)
        assertInflateFails(overflow.toByteArray(), "invalid bit length repeat")

        val noEnd = DeflateWriter()
        dynamicHeader(noEnd, 257, 1, zerosOnly)
        emitZeros(noEnd, codes(zerosOnly), 258)
        assertInflateFails(noEnd.toByteArray(), "invalid code -- missing end-of-block")

        val ones = mapOf(1 to 1, 18 to 1)
        val badLengths = DeflateWriter()
        dynamicHeader(badLengths, 257, 1, ones)
        val c = codes(ones)
        repeat(3) { emit(badLengths, c, 1) }
        emitZeros(badLengths, c, 253)
        emit(badLengths, c, 1)
        emit(badLengths, c, 1)
        assertInflateFails(badLengths.toByteArray(), "invalid literal/lengths set")

        val badDistances = DeflateWriter()
        dynamicHeader(badDistances, 257, 3, ones)
        emit(badDistances, c, 1)
        emitZeros(badDistances, c, 255)
        emit(badDistances, c, 1)
        repeat(3) { emit(badDistances, c, 1) }
        assertInflateFails(badDistances.toByteArray(), "invalid distances set")
    }

    @Test
    fun badZlibHeaders() {
        assertInflateFails(byteArrayOf(0x78, 0x9d.toByte()), "incorrect header check", ZlibFormat.ZLIB)
        assertInflateFails(zlibHeader(0x77), "unknown compression method", ZlibFormat.ZLIB)
        assertInflateFails(zlibHeader(0x88), "invalid window size", ZlibFormat.ZLIB)
        val gz = compress("x".encodeToByteArray(), format = ZlibFormat.GZIP)
        assertInflateFails(gz, "incorrect header check", ZlibFormat.ZLIB)
        val packed = compress("check".encodeToByteArray())
        packed[packed.size - 2] = (packed[packed.size - 2].toInt() xor 1).toByte()
        assertInflateFails(packed, "incorrect data check", ZlibFormat.ZLIB)
    }

    @Test
    fun badGzipHeaders() {
        val gz = compress("gzip data".encodeToByteArray(), format = ZlibFormat.GZIP)
        assertInflateFails(compress("x".encodeToByteArray()), "incorrect header check", ZlibFormat.GZIP)
        assertInflateFails(gz.copyOf().also { it[2] = 7 }, "unknown compression method", ZlibFormat.GZIP)
        assertInflateFails(gz.copyOf().also { it[3] = 0x20 }, "unknown header flags set", ZlibFormat.GZIP)
        assertInflateFails(gz.copyOf().also { it[gz.size - 4]++ }, "incorrect length check", ZlibFormat.GZIP)
        assertInflateFails(gz.copyOf().also { it[gz.size - 8]++ }, "incorrect data check", ZlibFormat.GZIP)
    }

    @Test
    fun rawDataIsNotZlib() {
        // A stored block starts with 0x01, which is not a valid zlib header.
        val raw = compress("raw".encodeToByteArray(), 0, ZlibFormat.RAW)
        assertInflateFails(raw, "incorrect header check", ZlibFormat.ZLIB)
        assertInflateFails(raw, "incorrect header check", ZlibFormat.GZIP)
    }

    @Test
    fun truncatedStreamsStallWithoutError() {
        val data = samples().first { it.first == "mixed" }.second.copyOf(6000)
        for (format in ZlibFormat.entries) {
            val packed = compress(data, 6, format)
            val step = maxOf(1, packed.size / 300)
            for (n in (0 until packed.size step step) + (packed.size - 1)) {
                val e = assertFailsWith<IllegalStateException>("$format prefix $n") {
                    decompress(packed.copyOf(n), format, inChunk = 1000, outChunk = 1000)
                }
                assertTrue(e.message.orEmpty().startsWith("no progress"), "$format prefix $n: $e")
            }
        }
    }

    @Test
    fun finishedInflaterReportsEnd() {
        val packed = compress("done".encodeToByteArray())
        val inf = Inflater()
        val out = ByteArray(16)
        assertTrue(inf.inflate(packed, 0, packed.size, out, 0, out.size).streamEnd)
        val again = inf.inflate(packed, 0, packed.size, out, 0, out.size)
        assertEquals(0, again.bytesConsumed)
        assertEquals(0, again.bytesProduced)
        assertTrue(again.streamEnd)
    }

    @Test
    fun rangeChecks() {
        val inf = Inflater()
        val buf = ByteArray(10)
        assertFailsWith<IndexOutOfBoundsException> { inf.inflate(buf, -1, 1, buf, 0, 10) }
        assertFailsWith<IndexOutOfBoundsException> { inf.inflate(buf, 5, 6, buf, 0, 10) }
        assertFailsWith<IndexOutOfBoundsException> { inf.inflate(buf, 0, 1, buf, 0, 11) }
        val d = Deflater()
        assertFailsWith<IndexOutOfBoundsException> { d.deflate(buf, 0, -1, buf, 0, 10) }
        assertFailsWith<IndexOutOfBoundsException> { d.deflate(buf, 0, 1, buf, 9, 2) }
    }

    private fun zlibHeader(cmf: Int): ByteArray {
        var flg = 0
        while ((cmf * 256 + flg) % 31 != 0) flg++
        return byteArrayOf(cmf.toByte(), flg.toByte(), 0, 0, 0, 0)
    }

    /** Writes a dynamic block header with the given code length code lengths (by symbol). */
    private fun dynamicHeader(w: DeflateWriter, hlit: Int, hdist: Int, clens: Map<Int, Int>) {
        val order = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val hclen = maxOf(4, order.indexOfLast { clens.containsKey(it) } + 1)
        w.header(true, 2).bits(hlit - 257, 5).bits(hdist - 1, 5).bits(hclen - 4, 4)
        for (i in 0 until hclen) w.bits(clens[order[i]] ?: 0, 3)
    }

    /** Canonical Huffman codes (RFC 1951 3.2.2) as symbol to (code, length). */
    private fun codes(lengths: Map<Int, Int>): Map<Int, Pair<Int, Int>> {
        val result = mutableMapOf<Int, Pair<Int, Int>>()
        var code = 0
        for (len in 1..15) {
            for (sym in lengths.keys.sorted()) if (lengths[sym] == len) result[sym] = code++ to len
            code = code shl 1
        }
        return result
    }

    private fun emit(w: DeflateWriter, codes: Map<Int, Pair<Int, Int>>, sym: Int): DeflateWriter {
        val (code, len) = codes.getValue(sym)
        return w.code(code, len)
    }

    /** Emits [n] zero code lengths with symbol 18 (11 to 138 at a time). */
    private fun emitZeros(w: DeflateWriter, codes: Map<Int, Pair<Int, Int>>, n: Int) {
        var left = n
        while (left > 0) {
            if (left < 11) {
                repeat(left) { emit(w, codes, 0) }
                return
            }
            val k = minOf(138, left)
            emit(w, codes, 18).bits(k - 11, 7)
            left -= k
        }
    }
}
