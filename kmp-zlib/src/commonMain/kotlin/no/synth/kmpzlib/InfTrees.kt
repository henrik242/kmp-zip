package no.synth.kmpzlib

// Ported from pako (src/zlib/inftrees.mjs), itself a port of zlib's inftrees.c.
// Table entries are packed as bits << 24 | op << 16 | val.

private const val MAXBITS = 15
internal const val ENOUGH_LENS = 852
internal const val ENOUGH_DISTS = 592

internal const val CODES = 0
internal const val LENS = 1
internal const val DISTS = 2

private val LBASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
    35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258, 0, 0,
)
private val LEXT = intArrayOf(
    16, 16, 16, 16, 16, 16, 16, 16, 17, 17, 17, 17, 18, 18, 18, 18,
    19, 19, 19, 19, 20, 20, 20, 20, 21, 21, 21, 21, 16, 199, 75,
)
private val DBASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
    257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
    8193, 12289, 16385, 24577, 0, 0,
)
private val DEXT = intArrayOf(
    16, 16, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21, 21, 22, 22,
    23, 23, 24, 24, 25, 25, 26, 26, 27, 27, 28, 28, 29, 29, 64, 64,
)

/**
 * Builds the decoding table for [codes] code lengths starting at [lensIndex].
 * Returns the root table bit count, or -1 if the lengths are invalid or the
 * table would not fit.
 */
internal fun inflateTable(
    type: Int,
    lens: IntArray,
    lensIndex: Int,
    codes: Int,
    table: IntArray,
    tableIndex: Int,
    work: IntArray,
    bits: Int,
): Int {
    val count = IntArray(MAXBITS + 1)
    val offs = IntArray(MAXBITS + 1)

    for (sym in 0 until codes) count[lens[lensIndex + sym]]++

    var root = bits
    var max = MAXBITS
    while (max >= 1) {
        if (count[max] != 0) break
        max--
    }
    if (root > max) root = max
    if (max == 0) {
        // No symbols: make a table that flags an invalid code on any input.
        table[tableIndex] = (1 shl 24) or (64 shl 16)
        table[tableIndex + 1] = (1 shl 24) or (64 shl 16)
        return 1
    }
    var min = 1
    while (min < max) {
        if (count[min] != 0) break
        min++
    }
    if (root < min) root = min

    var left = 1
    for (len in 1..MAXBITS) {
        left = left shl 1
        left -= count[len]
        if (left < 0) return -1 // over-subscribed
    }
    if (left > 0 && (type == CODES || max != 1)) return -1 // incomplete

    offs[1] = 0
    for (len in 1 until MAXBITS) offs[len + 1] = offs[len] + count[len]

    for (sym in 0 until codes) {
        if (lens[lensIndex + sym] != 0) work[offs[lens[lensIndex + sym]]++] = sym
    }

    val base: IntArray
    val extra: IntArray
    val match: Int
    when (type) {
        CODES -> {
            base = work
            extra = work
            match = 20
        }
        LENS -> {
            base = LBASE
            extra = LEXT
            match = 257
        }
        else -> {
            base = DBASE
            extra = DEXT
            match = 0
        }
    }

    var huff = 0
    var sym = 0
    var len = min
    var next = tableIndex
    var curr = root
    var drop = 0
    var low = -1
    var used = 1 shl root
    val mask = used - 1

    if ((type == LENS && used > ENOUGH_LENS) || (type == DISTS && used > ENOUGH_DISTS)) return -1

    while (true) {
        val hereBits = len - drop
        val hereOp: Int
        val hereVal: Int
        if (work[sym] + 1 < match) {
            hereOp = 0
            hereVal = work[sym]
        } else if (work[sym] >= match) {
            hereOp = extra[work[sym] - match]
            hereVal = base[work[sym] - match]
        } else {
            hereOp = 32 + 64 // end of block
            hereVal = 0
        }

        var incr = 1 shl (len - drop)
        var fill = 1 shl curr
        min = fill
        do {
            fill -= incr
            table[next + (huff shr drop) + fill] = (hereBits shl 24) or (hereOp shl 16) or hereVal
        } while (fill != 0)

        incr = 1 shl (len - 1)
        while ((huff and incr) != 0) incr = incr shr 1
        if (incr != 0) {
            huff = huff and (incr - 1)
            huff += incr
        } else {
            huff = 0
        }

        sym++
        if (--count[len] == 0) {
            if (len == max) break
            len = lens[lensIndex + work[sym]]
        }

        if (len > root && (huff and mask) != low) {
            if (drop == 0) drop = root
            next += min
            curr = len - drop
            left = 1 shl curr
            while (curr + drop < max) {
                left -= count[curr + drop]
                if (left <= 0) break
                curr++
                left = left shl 1
            }
            used += 1 shl curr
            if ((type == LENS && used > ENOUGH_LENS) || (type == DISTS && used > ENOUGH_DISTS)) return -1

            low = huff and mask
            table[low] = (root shl 24) or (curr shl 16) or (next - tableIndex)
        }
    }

    // Incomplete code: fill the remaining entry with an invalid-code marker.
    if (huff != 0) table[next + huff] = ((len - drop) shl 24) or (64 shl 16)
    return root
}
