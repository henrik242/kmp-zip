package no.synth.kmpzlib

import kotlin.jvm.JvmOverloads

// Ported from pako (src/zlib/adler32.mjs, src/zlib/crc32.mjs), itself a port of zlib.

// The most bytes summed between modulo reductions. With s1 and s2 below 65521 at the
// start, s2 stays below 2^31 for up to 3854 bytes of 0xff. pako uses 2000. A plain
// loop is kept: unrolling it is slower on wasmJs and gains little elsewhere.
private const val ADLER_NMAX = 3800
private const val ADLER_BASE = 65521

internal fun adler32(adler: Int, buf: ByteArray, len: Int, pos: Int): Int {
    var s1 = adler and 0xffff
    var s2 = (adler ushr 16) and 0xffff
    var p = pos
    var remaining = len
    while (remaining != 0) {
        var n = if (remaining > ADLER_NMAX) ADLER_NMAX else remaining
        remaining -= n
        do {
            s1 += buf[p++].toInt() and 0xff
            s2 += s1
        } while (--n != 0)
        s1 %= ADLER_BASE
        s2 %= ADLER_BASE
    }
    return s1 or (s2 shl 16)
}

// Slicing-by-4: table k maps a byte to its CRC after k more zero bytes, so four input
// bytes are folded in per step with independent lookups. Slicing-by-8 is faster on the
// JVM and js but slower than the plain byte loop on wasmJs; by-4 is faster on all.
private val CRC_TABLE = IntArray(4 * 256).also { table ->
    for (n in 0 until 256) {
        var c = n
        repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        table[n] = c
    }
    for (n in 0 until 256) {
        var c = table[n]
        for (k in 1 until 4) {
            c = table[c and 0xff] xor (c ushr 8)
            table[k * 256 + n] = c
        }
    }
}

// buf[i].toInt() sign-extends, setting bits 8-31 for bytes >= 0x80. Each index masks
// with `and 0xff` after the xor, which drops them; keep the masks.
internal fun crc32(crc: Int, buf: ByteArray, len: Int, pos: Int): Int {
    val t = CRC_TABLE
    var c = crc.inv()
    var i = pos
    val end = pos + len
    while (i <= end - 4) {
        c = t[3 * 256 + ((c xor buf[i++].toInt()) and 0xff)] xor
            t[2 * 256 + (((c ushr 8) xor buf[i++].toInt()) and 0xff)] xor
            t[256 + (((c ushr 16) xor buf[i++].toInt()) and 0xff)] xor
            t[((c ushr 24) xor buf[i++].toInt()) and 0xff]
    }
    while (i < end) {
        c = (c ushr 8) xor t[(c xor buf[i++].toInt()) and 0xff]
    }
    return c.inv()
}

/**
 * CRC-32 (IEEE 802.3, as used by ZIP, gzip and PNG), like `java.util.zip.CRC32`.
 *
 * Not thread-safe.
 */
public class Crc32 {
    private var crc = 0

    /** Adds `buf[offset, offset + length)` to the checksum. */
    @JvmOverloads
    @Throws(IndexOutOfBoundsException::class)
    public fun update(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset) {
        checkRange(buf, offset, length)
        crc = crc32(crc, buf, length, offset)
    }

    /** The checksum of the bytes added since construction or [reset], as an unsigned value. */
    public val value: Long get() = crc.toLong() and 0xFFFFFFFFL

    public fun reset() {
        crc = 0
    }
}
