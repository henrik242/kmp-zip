package no.synth.kmpzlib

/**
 * Writes raw DEFLATE bit streams by hand, for inputs a real compressor never makes
 * (32 KiB distances, chosen match lengths, broken headers). [expected] tracks what
 * an inflater should produce for the literals and matches written so far.
 */
class DeflateWriter {
    private val out = Sink()
    private val one = ByteArray(1)
    private var acc = 0
    private var count = 0
    private var history = ByteArray(1024)
    private var size = 0

    /** Writes the low [n] bits of [value], least significant first. */
    fun bits(value: Int, n: Int): DeflateWriter {
        for (i in 0 until n) {
            acc = acc or (((value ushr i) and 1) shl count)
            if (++count == 8) flushByte()
        }
        return this
    }

    /** Writes a Huffman code of [len] bits, most significant first. */
    fun code(code: Int, len: Int): DeflateWriter {
        for (i in len - 1 downTo 0) bits((code ushr i) and 1, 1)
        return this
    }

    fun alignToByte(): DeflateWriter {
        if (count > 0) flushByte()
        return this
    }

    fun header(last: Boolean, type: Int): DeflateWriter = bits(if (last) 1 else 0, 1).bits(type, 2)

    fun stored(data: ByteArray, last: Boolean): DeflateWriter {
        header(last, 0).alignToByte()
        bits(data.size, 16).bits(data.size.inv(), 16)
        for (b in data) bits(b.toInt(), 8)
        for (b in data) record(b)
        return this
    }

    /** A literal/length symbol from the fixed code. */
    fun fixedSymbol(sym: Int): DeflateWriter = when {
        sym < 144 -> code(0x30 + sym, 8)
        sym < 256 -> code(0x190 + sym - 144, 9)
        sym < 280 -> code(sym - 256, 7)
        else -> code(0xc0 + sym - 280, 8)
    }

    fun fixedLiteral(b: Int): DeflateWriter {
        fixedSymbol(b)
        record(b.toByte())
        return this
    }

    fun fixedLiterals(data: ByteArray): DeflateWriter {
        for (b in data) fixedLiteral(b.toInt() and 0xff)
        return this
    }

    fun fixedEnd(): DeflateWriter = fixedSymbol(256)

    /** A match in the fixed code. Does not check that [dist] is valid. */
    fun fixedMatch(len: Int, dist: Int): DeflateWriter {
        require(len in 3..258 && dist in 1..32768)
        val l = LEN_BASE.indexOfLast { it <= len }
        fixedSymbol(257 + l).bits(len - LEN_BASE[l], LEN_EXTRA[l])
        val d = DIST_BASE.indexOfLast { it <= dist }
        code(d, 5).bits(dist - DIST_BASE[d], DIST_EXTRA[d])
        if (dist <= size) for (i in 0 until len) record(history[size - dist])
        return this
    }

    fun toByteArray(): ByteArray {
        alignToByte()
        return out.toByteArray()
    }

    val expected: ByteArray get() = history.copyOf(size)

    private fun flushByte() {
        one[0] = acc.toByte()
        out.write(one, 1)
        acc = 0
        count = 0
    }

    private fun record(b: Byte) {
        if (size == history.size) history = history.copyOf(size * 2)
        history[size++] = b
    }

    companion object {
        val LEN_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
        )
        val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    }
}

/** Wraps a raw DEFLATE stream as zlib, with the Adler-32 of [data]. */
fun zlibWrap(raw: ByteArray, data: ByteArray): ByteArray {
    val a = adler32(1, data, data.size, 0)
    return byteArrayOf(0x78, 0x9c.toByte()) + raw +
        byteArrayOf((a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte())
}

/** Wraps a raw DEFLATE stream as gzip, with the CRC-32 and size of [data]. */
fun gzipWrap(raw: ByteArray, data: ByteArray): ByteArray {
    val c = crc32(0, data, data.size, 0)
    val n = data.size
    return byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 3) + raw + byteArrayOf(
        c.toByte(), (c ushr 8).toByte(), (c ushr 16).toByte(), (c ushr 24).toByte(),
        n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte(),
    )
}
