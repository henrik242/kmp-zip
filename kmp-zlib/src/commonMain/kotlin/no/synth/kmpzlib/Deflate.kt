package no.synth.kmpzlib

// Ported from pako (src/zlib/deflate.mjs), itself a port of zlib's deflate.c.
// Differences from pako: only the default strategy, only Z_NO_FLUSH and Z_FINISH, no
// custom gzip header (the default one is always written), no preset dictionary, and
// only pako 3's default (non-legacy) hash.

private const val MAX_MEM_LEVEL = 9
private const val MIN_MATCH = 3
private const val MAX_MATCH = 258
private const val MIN_LOOKAHEAD = MAX_MATCH + MIN_MATCH + 1

private const val INIT_STATE = 42
private const val GZIP_STATE = 57
private const val BUSY_STATE = 113
private const val FINISH_STATE = 666

private const val BS_NEED_MORE = 1
private const val BS_FINISH_STARTED = 3
private const val BS_FINISH_DONE = 4

private const val OS_CODE = 3

// longest_match and the 4-byte hash may read a few bytes past the filled part of
// the window; JS reads `undefined` there, so pad with zeros to match.
private const val WINDOW_PADDING = 8

private class Config(
    val goodLength: Int,
    val maxLazy: Int,
    val niceLength: Int,
    val maxChain: Int,
    val func: Int,
)

private const val FUNC_STORED = 0
private const val FUNC_FAST = 1
private const val FUNC_SLOW = 2

private val CONFIGURATION_TABLE = arrayOf(
    Config(0, 0, 0, 0, FUNC_STORED),
    Config(4, 4, 8, 4, FUNC_FAST),
    Config(4, 5, 16, 8, FUNC_FAST),
    Config(4, 6, 32, 32, FUNC_FAST),
    Config(4, 4, 16, 16, FUNC_SLOW),
    Config(8, 16, 32, 32, FUNC_SLOW),
    Config(8, 16, 128, 128, FUNC_SLOW),
    Config(8, 32, 128, 256, FUNC_SLOW),
    Config(32, 128, 258, 1024, FUNC_SLOW),
    Config(32, 258, 258, 4096, FUNC_SLOW),
)

internal class DeflateState(
    val strm: ZStream,
    val level: Int,
    windowBits: Int,
    memLevel: Int,
    /** 0 raw, 1 zlib, 2 gzip; negated once the trailer has been written. */
    var wrap: Int,
) {
    var status = INIT_STATE
    /** Flush mode of the previous call; -1 after a call that filled the output, -2 when fresh. */
    var lastFlush = -2

    val wBits = windowBits
    val wSize = 1 shl wBits
    val wMask = wSize - 1
    val window = ByteArray(wSize * 2 + WINDOW_PADDING)
    var windowSize = 0
    val prev = IntArray(wSize)

    val hashBits = maxOf(memLevel + 7, 15)
    val hashSize = 1 shl hashBits
    val hashMask = hashSize - 1
    val head = IntArray(hashSize)

    var blockStart = 0
    var matchLength = 0
    var prevMatch = 0
    var matchAvailable = false
    var strstart = 0
    var matchStart = 0
    var lookahead = 0
    var prevLength = 0
    var maxChainLength = 0
    var maxLazyMatch = 0
    var goodMatch = 0
    var niceMatch = 0

    val dynLtree = IntArray(HEAP_SIZE * 2)
    val dynDtree = IntArray((2 * D_CODES + 1) * 2)
    val blTree = IntArray((2 * BL_CODES + 1) * 2)
    lateinit var lDesc: TreeDesc
    lateinit var dDesc: TreeDesc
    lateinit var blDesc: TreeDesc

    val blCount = IntArray(MAX_BITS + 1)
    val heap = IntArray(2 * L_CODES + 1)
    var heapLen = 0
    var heapMax = 0
    val depth = IntArray(2 * L_CODES + 1)

    val litBufsize = 1 shl (memLevel + 6)
    val pendingBufSize = litBufsize * 4
    val pendingBuf = ByteArray(pendingBufSize)
    var pendingOut = 0
    var pending = 0
    val symBuf = litBufsize
    var symNext = 0
    val symEnd = (litBufsize - 1) * 3

    var optLen = 0
    var staticLen = 0
    var insert = 0
    var biBuf = 0
    var biValid = 0
}

internal fun deflateInit2(
    strm: ZStream,
    level: Int,
    windowBits: Int,
    memLevel: Int,
): DeflateState {
    var lvl = level
    var wbits = windowBits
    var wrap = 1
    if (lvl == -1) lvl = 6
    if (wbits < 0) {
        wrap = 0
        wbits = -wbits
    } else if (wbits > 15) {
        wrap = 2
        wbits -= 16
    }
    require(memLevel in 1..MAX_MEM_LEVEL) { "Invalid memLevel: $memLevel" }
    require(wbits in 8..15 && !(wbits == 8 && wrap != 1)) { "Invalid windowBits: $windowBits" }
    require(lvl in 0..9) { "Invalid level: $level" }
    if (wbits == 8) wbits = 9

    val s = DeflateState(strm, lvl, wbits, memLevel, wrap)
    deflateReset(s)
    return s
}

internal fun deflateReset(s: DeflateState) {
    val strm = s.strm
    strm.totalIn = 0
    s.pending = 0
    s.pendingOut = 0
    if (s.wrap < 0) s.wrap = -s.wrap
    s.status = if (s.wrap == 2) GZIP_STATE else if (s.wrap != 0) INIT_STATE else BUSY_STATE
    strm.adler = if (s.wrap == 2) 0 else 1
    s.lastFlush = -2
    s.trInit()
    lmInit(s)
}

private fun lmInit(s: DeflateState) {
    s.windowSize = 2 * s.wSize
    s.head.fill(0)
    val config = CONFIGURATION_TABLE[s.level]
    s.maxLazyMatch = config.maxLazy
    s.goodMatch = config.goodLength
    s.niceMatch = config.niceLength
    s.maxChainLength = config.maxChain
    s.strstart = 0
    s.blockStart = 0
    s.lookahead = 0
    s.insert = 0
    s.matchLength = MIN_MATCH - 1
    s.prevLength = MIN_MATCH - 1
    s.matchAvailable = false
}

private fun slideHash(s: DeflateState) {
    val wsize = s.wSize
    val head = s.head
    for (p in 0 until s.hashSize) {
        val m = head[p]
        head[p] = if (m >= wsize) m - wsize else 0
    }
    val prev = s.prev
    for (p in 0 until wsize) {
        val m = prev[p]
        prev[p] = if (m >= wsize) m - wsize else 0
    }
}

private fun insertString(s: DeflateState, str: Int): Int {
    val w = s.window
    val value = w.u8(str) or (w.u8(str + 1) shl 8) or (w.u8(str + 2) shl 16) or (w.u8(str + 3) shl 24)
    val h = ((value * 66521 + 66521) ushr 16) and s.hashMask
    val hashHead = s.head[h]
    s.prev[str and s.wMask] = hashHead
    s.head[h] = str
    return hashHead
}

private fun flushPending(strm: ZStream, s: DeflateState) {
    var len = s.pending
    if (len > strm.availOut) len = strm.availOut
    if (len == 0) return
    s.pendingBuf.copyInto(strm.output, strm.nextOut, s.pendingOut, s.pendingOut + len)
    strm.nextOut += len
    s.pendingOut += len
    strm.availOut -= len
    s.pending -= len
    if (s.pending == 0) s.pendingOut = 0
}

private fun flushBlockOnly(s: DeflateState, last: Boolean) {
    s.trFlushBlock(if (s.blockStart >= 0) s.blockStart else -1, s.strstart - s.blockStart, last)
    s.blockStart = s.strstart
    flushPending(s.strm, s)
}

private fun putByte(s: DeflateState, b: Int) {
    s.pendingBuf[s.pending++] = b.toByte()
}

private fun putShortMSB(s: DeflateState, b: Int) {
    s.pendingBuf[s.pending++] = (b ushr 8).toByte()
    s.pendingBuf[s.pending++] = b.toByte()
}

private fun readBuf(s: DeflateState, buf: ByteArray, start: Int, size: Int): Int {
    val strm = s.strm
    var len = strm.availIn
    if (len > size) len = size
    if (len == 0) return 0
    strm.availIn -= len
    strm.input.copyInto(buf, start, strm.nextIn, strm.nextIn + len)
    if (s.wrap == 1) {
        strm.adler = adler32(strm.adler, buf, len, start)
    } else if (s.wrap == 2) {
        strm.adler = crc32(strm.adler, buf, len, start)
    }
    strm.nextIn += len
    strm.totalIn += len
    return len
}

private fun longestMatch(s: DeflateState, startMatch: Int): Int {
    var curMatch = startMatch
    var chainLength = s.maxChainLength
    var scan = s.strstart
    var bestLen = s.prevLength
    var niceMatch = s.niceMatch
    val limit = if (s.strstart > s.wSize - MIN_LOOKAHEAD) s.strstart - (s.wSize - MIN_LOOKAHEAD) else 0
    val win = s.window
    val wmask = s.wMask
    val prev = s.prev
    val strend = s.strstart + MAX_MATCH
    var scanEnd1 = win[scan + bestLen - 1]
    var scanEnd = win[scan + bestLen]

    if (s.prevLength >= s.goodMatch) chainLength = chainLength shr 2
    if (niceMatch > s.lookahead) niceMatch = s.lookahead

    do {
        var match = curMatch
        if (win[match + bestLen] != scanEnd ||
            win[match + bestLen - 1] != scanEnd1 ||
            win[match] != win[scan] ||
            win[++match] != win[scan + 1]
        ) {
            continue
        }
        scan += 2
        match++
        @Suppress("ControlFlowWithEmptyBody")
        do {
        } while (win[++scan] == win[++match] && win[++scan] == win[++match] &&
            win[++scan] == win[++match] && win[++scan] == win[++match] &&
            win[++scan] == win[++match] && win[++scan] == win[++match] &&
            win[++scan] == win[++match] && win[++scan] == win[++match] &&
            scan < strend
        )
        val len = MAX_MATCH - (strend - scan)
        scan = strend - MAX_MATCH
        if (len > bestLen) {
            s.matchStart = curMatch
            bestLen = len
            if (len >= niceMatch) break
            scanEnd1 = win[scan + bestLen - 1]
            scanEnd = win[scan + bestLen]
        }
    } while (prev[curMatch and wmask].also { curMatch = it } > limit && --chainLength != 0)

    return if (bestLen <= s.lookahead) bestLen else s.lookahead
}

private fun fillWindow(s: DeflateState) {
    val wSize = s.wSize
    do {
        var more = s.windowSize - s.lookahead - s.strstart
        if (s.strstart >= wSize + (wSize - MIN_LOOKAHEAD)) {
            s.window.copyInto(s.window, 0, wSize, wSize + wSize - more)
            s.matchStart -= wSize
            s.strstart -= wSize
            s.blockStart -= wSize
            if (s.insert > s.strstart) s.insert = s.strstart
            slideHash(s)
            more += wSize
        }
        if (s.strm.availIn == 0) break

        val n = readBuf(s, s.window, s.strstart + s.lookahead, more)
        s.lookahead += n

        if (s.lookahead + s.insert > MIN_MATCH) {
            var str = s.strstart - s.insert
            while (s.insert != 0) {
                insertString(s, str)
                str++
                s.insert--
                if (s.lookahead + s.insert <= MIN_MATCH) break
            }
        }
    } while (s.lookahead < MIN_LOOKAHEAD && s.strm.availIn != 0)
}

private fun deflateStored(s: DeflateState, flush: Int): Int {
    val strm = s.strm
    var minBlock = if (s.pendingBufSize - 5 > s.wSize) s.wSize else s.pendingBufSize - 5
    var len: Int
    var left: Int
    var have: Int
    var last = false
    var used = strm.availIn

    do {
        len = 65535
        have = (s.biValid + 42) shr 3
        if (strm.availOut < have) break
        have = strm.availOut - have
        left = s.strstart - s.blockStart
        if (len > left + strm.availIn) len = left + strm.availIn
        if (len > have) len = have

        if (len < minBlock &&
            ((len == 0 && flush != Z_FINISH) || flush == Z_NO_FLUSH || len != left + strm.availIn)
        ) {
            break
        }

        last = flush == Z_FINISH && len == left + strm.availIn
        s.trStoredBlock(0, 0, last)

        s.pendingBuf[s.pending - 4] = len.toByte()
        s.pendingBuf[s.pending - 3] = (len shr 8).toByte()
        s.pendingBuf[s.pending - 2] = len.inv().toByte()
        s.pendingBuf[s.pending - 1] = (len.inv() shr 8).toByte()

        flushPending(strm, s)

        if (left != 0) {
            if (left > len) left = len
            s.window.copyInto(strm.output, strm.nextOut, s.blockStart, s.blockStart + left)
            strm.nextOut += left
            strm.availOut -= left
            s.blockStart += left
            len -= left
        }

        if (len != 0) {
            readBuf(s, strm.output, strm.nextOut, len)
            strm.nextOut += len
            strm.availOut -= len
        }
    } while (!last)

    used -= strm.availIn
    if (used != 0) {
        if (used >= s.wSize) {
            strm.input.copyInto(s.window, 0, strm.nextIn - s.wSize, strm.nextIn)
            s.strstart = s.wSize
            s.insert = s.strstart
        } else {
            if (s.windowSize - s.strstart <= used) {
                s.strstart -= s.wSize
                s.window.copyInto(s.window, 0, s.wSize, s.wSize + s.strstart)
                if (s.insert > s.strstart) s.insert = s.strstart
            }
            strm.input.copyInto(s.window, s.strstart, strm.nextIn - used, strm.nextIn)
            s.strstart += used
            s.insert += if (used > s.wSize - s.insert) s.wSize - s.insert else used
        }
        s.blockStart = s.strstart
    }

    if (last) return BS_FINISH_DONE

    have = s.windowSize - s.strstart
    if (strm.availIn > have && s.blockStart >= s.wSize) {
        s.blockStart -= s.wSize
        s.strstart -= s.wSize
        s.window.copyInto(s.window, 0, s.wSize, s.wSize + s.strstart)
        have += s.wSize
        if (s.insert > s.strstart) s.insert = s.strstart
    }
    if (have > strm.availIn) have = strm.availIn
    if (have != 0) {
        readBuf(s, s.window, s.strstart, have)
        s.strstart += have
        s.insert += if (have > s.wSize - s.insert) s.wSize - s.insert else have
    }

    have = (s.biValid + 42) shr 3
    have = if (s.pendingBufSize - have > 65535) 65535 else s.pendingBufSize - have
    minBlock = if (have > s.wSize) s.wSize else have
    left = s.strstart - s.blockStart
    if (left >= minBlock ||
        ((left != 0 || flush == Z_FINISH) && flush != Z_NO_FLUSH && strm.availIn == 0 && left <= have)
    ) {
        len = if (left > have) have else left
        last = flush == Z_FINISH && strm.availIn == 0 && len == left
        s.trStoredBlock(s.blockStart, len, last)
        s.blockStart += len
        flushPending(strm, s)
    }

    return if (last) BS_FINISH_STARTED else BS_NEED_MORE
}

/** Shared tail of the fast and slow block functions: input is used up and Z_FINISH is set. */
private fun finishBlock(s: DeflateState): Int {
    flushBlockOnly(s, true)
    return if (s.strm.availOut == 0) BS_FINISH_STARTED else BS_FINISH_DONE
}

private fun deflateFast(s: DeflateState, flush: Int): Int {
    while (true) {
        if (s.lookahead < MIN_LOOKAHEAD) {
            fillWindow(s)
            if (s.lookahead < MIN_LOOKAHEAD && flush == Z_NO_FLUSH) return BS_NEED_MORE
            if (s.lookahead == 0) break
        }

        var hashHead = 0
        if (s.lookahead >= MIN_MATCH) hashHead = insertString(s, s.strstart)

        if (hashHead != 0 && s.strstart - hashHead <= s.wSize - MIN_LOOKAHEAD) {
            s.matchLength = longestMatch(s, hashHead)
        }
        val bflush: Boolean
        if (s.matchLength >= MIN_MATCH) {
            bflush = s.trTally(s.strstart - s.matchStart, s.matchLength - MIN_MATCH)
            s.lookahead -= s.matchLength

            if (s.matchLength <= s.maxLazyMatch && s.lookahead >= MIN_MATCH) {
                s.matchLength--
                do {
                    s.strstart++
                    insertString(s, s.strstart)
                } while (--s.matchLength != 0)
                s.strstart++
            } else {
                s.strstart += s.matchLength
                s.matchLength = 0
            }
        } else {
            bflush = s.trTally(0, s.window.u8(s.strstart))
            s.lookahead--
            s.strstart++
        }
        if (bflush) {
            flushBlockOnly(s, false)
            if (s.strm.availOut == 0) return BS_NEED_MORE
        }
    }
    s.insert = if (s.strstart < MIN_MATCH - 1) s.strstart else MIN_MATCH - 1
    return finishBlock(s)
}

private fun deflateSlow(s: DeflateState, flush: Int): Int {
    while (true) {
        if (s.lookahead < MIN_LOOKAHEAD) {
            fillWindow(s)
            if (s.lookahead < MIN_LOOKAHEAD && flush == Z_NO_FLUSH) return BS_NEED_MORE
            if (s.lookahead == 0) break
        }

        var hashHead = 0
        if (s.lookahead >= MIN_MATCH) hashHead = insertString(s, s.strstart)

        s.prevLength = s.matchLength
        s.prevMatch = s.matchStart
        s.matchLength = MIN_MATCH - 1

        if (hashHead != 0 && s.prevLength < s.maxLazyMatch &&
            s.strstart - hashHead <= s.wSize - MIN_LOOKAHEAD
        ) {
            s.matchLength = longestMatch(s, hashHead)
            if (s.matchLength == MIN_MATCH && s.strstart - s.matchStart > 4096) {
                s.matchLength = MIN_MATCH - 1
            }
        }

        if (s.prevLength >= MIN_MATCH && s.matchLength <= s.prevLength) {
            val maxInsert = s.strstart + s.lookahead - MIN_MATCH
            val bflush = s.trTally(s.strstart - 1 - s.prevMatch, s.prevLength - MIN_MATCH)
            s.lookahead -= s.prevLength - 1
            s.prevLength -= 2
            do {
                if (++s.strstart <= maxInsert) insertString(s, s.strstart)
            } while (--s.prevLength != 0)
            s.matchAvailable = false
            s.matchLength = MIN_MATCH - 1
            s.strstart++

            if (bflush) {
                flushBlockOnly(s, false)
                if (s.strm.availOut == 0) return BS_NEED_MORE
            }
        } else if (s.matchAvailable) {
            val bflush = s.trTally(0, s.window.u8(s.strstart - 1))
            if (bflush) flushBlockOnly(s, false)
            s.strstart++
            s.lookahead--
            if (s.strm.availOut == 0) return BS_NEED_MORE
        } else {
            s.matchAvailable = true
            s.strstart++
            s.lookahead--
        }
    }
    if (s.matchAvailable) {
        s.trTally(0, s.window.u8(s.strstart - 1))
        s.matchAvailable = false
    }
    s.insert = if (s.strstart < MIN_MATCH - 1) s.strstart else MIN_MATCH - 1
    return finishBlock(s)
}

/**
 * [flush] is Z_NO_FLUSH or Z_FINISH. Returns Z_OK, Z_STREAM_END, Z_BUF_ERROR (no progress
 * possible) or Z_STREAM_ERROR (called again without Z_FINISH once finishing).
 */
internal fun deflate(s: DeflateState, flush: Int): Int {
    val strm = s.strm
    if (s.status == FINISH_STATE && flush != Z_FINISH) {
        return err(strm, if (strm.availOut == 0) Z_BUF_ERROR else Z_STREAM_ERROR)
    }

    val oldFlush = s.lastFlush
    s.lastFlush = flush

    if (s.pending != 0) {
        flushPending(strm, s)
        if (strm.availOut == 0) {
            s.lastFlush = -1
            return Z_OK
        }
    } else if (strm.availIn == 0 && flush == Z_NO_FLUSH && oldFlush >= 0) {
        // Nothing new since a call that didn't fill the output: no progress possible.
        return err(strm, Z_BUF_ERROR)
    }

    if (s.status == FINISH_STATE && strm.availIn != 0) return err(strm, Z_BUF_ERROR)

    if (s.status == INIT_STATE && s.wrap == 0) s.status = BUSY_STATE

    if (s.status == INIT_STATE) {
        var header = (8 + ((s.wBits - 8) shl 4)) shl 8
        val levelFlags = when {
            s.level < 2 -> 0
            s.level < 6 -> 1
            s.level == 6 -> 2
            else -> 3
        }
        header = header or (levelFlags shl 6)
        header += 31 - (header % 31)
        putShortMSB(s, header)
        strm.adler = 1
        s.status = BUSY_STATE

        flushPending(strm, s)
        if (s.pending != 0) {
            s.lastFlush = -1
            return Z_OK
        }
    }

    if (s.status == GZIP_STATE) {
        strm.adler = 0
        putByte(s, 31)
        putByte(s, 139)
        putByte(s, 8)
        putByte(s, 0) // flags
        putByte(s, 0) // mtime
        putByte(s, 0)
        putByte(s, 0)
        putByte(s, 0)
        putByte(s, if (s.level == 9) 2 else if (s.level < 2) 4 else 0)
        putByte(s, OS_CODE)
        s.status = BUSY_STATE

        flushPending(strm, s)
        if (s.pending != 0) {
            s.lastFlush = -1
            return Z_OK
        }
    }

    if (strm.availIn != 0 || s.lookahead != 0 || (flush != Z_NO_FLUSH && s.status != FINISH_STATE)) {
        val bstate = when {
            s.level == 0 -> deflateStored(s, flush)
            CONFIGURATION_TABLE[s.level].func == FUNC_FAST -> deflateFast(s, flush)
            else -> deflateSlow(s, flush)
        }

        if (bstate == BS_FINISH_STARTED || bstate == BS_FINISH_DONE) s.status = FINISH_STATE
        if (bstate == BS_NEED_MORE || bstate == BS_FINISH_STARTED) {
            if (strm.availOut == 0) s.lastFlush = -1
            return Z_OK
        }
    }

    if (flush != Z_FINISH) return Z_OK
    if (s.wrap <= 0) return Z_STREAM_END

    if (s.wrap == 2) {
        val adler = strm.adler
        putByte(s, adler)
        putByte(s, adler shr 8)
        putByte(s, adler shr 16)
        putByte(s, adler shr 24)
        val total = strm.totalIn.toInt()
        putByte(s, total)
        putByte(s, total shr 8)
        putByte(s, total shr 16)
        putByte(s, total shr 24)
    } else {
        putShortMSB(s, strm.adler ushr 16)
        putShortMSB(s, strm.adler and 0xffff)
    }

    flushPending(strm, s)
    // Write the trailer only once.
    if (s.wrap > 0) s.wrap = -s.wrap
    return if (s.pending != 0) Z_OK else Z_STREAM_END
}

private fun err(strm: ZStream, code: Int): Int {
    strm.msg = errorMessage(code)
    return code
}
