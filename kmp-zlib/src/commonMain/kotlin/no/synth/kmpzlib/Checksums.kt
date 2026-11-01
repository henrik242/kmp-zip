package no.synth.kmpzlib

// Ported from pako (src/zlib/adler32.mjs, src/zlib/crc32.mjs), itself a port of zlib.

internal fun adler32(adler: Int, buf: ByteArray, len: Int, pos: Int): Int {
    var s1 = adler and 0xffff
    var s2 = (adler ushr 16) and 0xffff
    var p = pos
    var remaining = len
    while (remaining != 0) {
        // 2000 keeps s2 below 2^31 between the modulo reductions.
        var n = if (remaining > 2000) 2000 else remaining
        remaining -= n
        do {
            s1 += buf[p++].toInt() and 0xff
            s2 += s1
        } while (--n != 0)
        s1 %= 65521
        s2 %= 65521
    }
    return s1 or (s2 shl 16)
}

private val CRC_TABLE = IntArray(256).also { table ->
    for (n in 0 until 256) {
        var c = n
        repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        table[n] = c
    }
}

internal fun crc32(crc: Int, buf: ByteArray, len: Int, pos: Int): Int {
    val t = CRC_TABLE
    var c = crc.inv()
    for (i in pos until pos + len) {
        c = (c ushr 8) xor t[(c xor buf[i].toInt()) and 0xff]
    }
    return c.inv()
}
