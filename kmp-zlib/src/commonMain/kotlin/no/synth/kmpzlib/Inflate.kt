package no.synth.kmpzlib

// Ported from pako (src/zlib/inflate.mjs, src/zlib/inffast.mjs), itself a port of
// zlib's inflate.c and inffast.c. Differences from pako: no gzip header capture, no
// preset dictionary (a zlib stream that needs one returns Z_NEED_DICT), and no
// flush modes other than Z_NO_FLUSH.

private const val HEAD = 16180
private const val FLAGS = 16181
private const val TIME = 16182
private const val OS = 16183
private const val EXLEN = 16184
private const val EXTRA = 16185
private const val NAME = 16186
private const val COMMENT = 16187
private const val HCRC = 16188
private const val DICTID = 16189
private const val DICT = 16190
private const val TYPE = 16191
private const val TYPEDO = 16192
private const val STORED = 16193
private const val COPY_ = 16194
private const val COPY = 16195
private const val TABLE = 16196
private const val LENLENS = 16197
private const val CODELENS = 16198
private const val LEN_ = 16199
private const val LEN = 16200
private const val LENEXT = 16201
private const val DIST = 16202
private const val DISTEXT = 16203
private const val MATCH = 16204
private const val LIT = 16205
private const val CHECK = 16206
private const val LENGTH = 16207
private const val DONE = 16208
private const val BAD = 16209

private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

private fun zswap32(q: Int): Int =
    ((q ushr 24) and 0xff) + ((q ushr 8) and 0xff00) + ((q and 0xff00) shl 8) + ((q and 0xff) shl 24)

private object FixedTables {
    val lenfix = IntArray(512)
    val distfix = IntArray(32)

    init {
        val lens = IntArray(320)
        val work = IntArray(288)
        var sym = 0
        while (sym < 144) lens[sym++] = 8
        while (sym < 256) lens[sym++] = 9
        while (sym < 280) lens[sym++] = 7
        while (sym < 288) lens[sym++] = 8
        inflateTable(LENS, lens, 0, 288, lenfix, 0, work, 9)
        sym = 0
        while (sym < 32) lens[sym++] = 5
        inflateTable(DISTS, lens, 0, 32, distfix, 0, work, 5)
    }
}

internal class InflateState(val strm: ZStream) {
    var mode = HEAD
    var last = false
    /** Bit 0: zlib, bit 1: gzip, bit 2: verify the check value. 0 for raw deflate. */
    var wrap = 0
    var flags = 0
    var check = 0
    var total = 0L
    var wbits = 0
    var wsize = 0
    var whave = 0
    var wnext = 0
    var window: ByteArray? = null
    var hold = 0
    var bits = 0
    var length = 0
    var offset = 0
    var extra = 0
    var lencode: IntArray = FixedTables.lenfix
    var distcode: IntArray = FixedTables.distfix
    var lenbits = 0
    var distbits = 0
    var ncode = 0
    var nlen = 0
    var ndist = 0
    var have = 0
    val lens = IntArray(320)
    val work = IntArray(288)
    val lendyn = IntArray(ENOUGH_LENS)
    val distdyn = IntArray(ENOUGH_DISTS)
}

internal fun inflateInit2(strm: ZStream, windowBits: Int): InflateState {
    val state = InflateState(strm)
    var wbits = windowBits
    val wrap: Int
    if (wbits < 0) {
        wrap = 0
        wbits = -wbits
    } else {
        wrap = (wbits shr 4) + 5
        wbits = wbits and 15
    }
    require(wbits in 8..15) { "Invalid windowBits: $windowBits" }
    state.wrap = wrap
    state.wbits = wbits
    inflateReset(state)
    return state
}

/** Resets to a fresh stream, keeping the wrapping mode and the allocated window. */
internal fun inflateReset(state: InflateState) {
    val strm = state.strm
    state.wsize = 0
    state.whave = 0
    state.wnext = 0
    state.total = 0
    strm.msg = ""
    if (state.wrap != 0) strm.adler = state.wrap and 1
    state.mode = HEAD
    state.last = false
    state.flags = -1
    state.hold = 0
    state.bits = 0
    state.lencode = state.lendyn
    state.distcode = state.distdyn
}

private fun fixedTables(state: InflateState) {
    state.lencode = FixedTables.lenfix
    state.lenbits = 9
    state.distcode = FixedTables.distfix
    state.distbits = 5
}

private fun updateWindow(state: InflateState, src: ByteArray, end: Int, copyIn: Int) {
    var copy = copyIn
    val window = state.window ?: ByteArray(1 shl state.wbits).also { state.window = it }

    if (state.wsize == 0) {
        state.wsize = 1 shl state.wbits
        state.wnext = 0
        state.whave = 0
    }

    if (copy >= state.wsize) {
        src.copyInto(window, 0, end - state.wsize, end)
        state.wnext = 0
        state.whave = state.wsize
    } else {
        var dist = state.wsize - state.wnext
        if (dist > copy) dist = copy
        src.copyInto(window, state.wnext, end - copy, end - copy + dist)
        copy -= dist
        if (copy != 0) {
            src.copyInto(window, 0, end - copy, end)
            state.wnext = copy
            state.whave = state.wsize
        } else {
            state.wnext += dist
            if (state.wnext == state.wsize) state.wnext = 0
            if (state.whave < state.wsize) state.whave += dist
        }
    }
}

/**
 * Decodes literal/length and distance codes while there are at least 6 input bytes
 * and 258 output bytes available. [start] is avail_out at the start of [inflate].
 */
private fun inflateFast(state: InflateState, start: Int) {
    val strm = state.strm
    val input = strm.input
    val output = strm.output
    var inp = strm.nextIn
    val last = inp + (strm.availIn - 5)
    var out = strm.nextOut
    val beg = out - (start - strm.availOut)
    val end = out + (strm.availOut - 257)
    val wsize = state.wsize
    val whave = state.whave
    val wnext = state.wnext
    val sWindow = state.window
    var hold = state.hold
    var bits = state.bits
    val lcode = state.lencode
    val dcode = state.distcode
    val lmask = (1 shl state.lenbits) - 1
    val dmask = (1 shl state.distbits) - 1

    top@ do {
        if (bits < 15) {
            hold += input.u8(inp++) shl bits
            bits += 8
            hold += input.u8(inp++) shl bits
            bits += 8
        }
        var here = lcode[hold and lmask]

        dolen@ while (true) {
            var op = here ushr 24
            hold = hold ushr op
            bits -= op
            op = (here ushr 16) and 0xff
            if (op == 0) {
                output[out++] = here.toByte()
            } else if ((op and 16) != 0) {
                var len = here and 0xffff
                op = op and 15
                if (op != 0) {
                    if (bits < op) {
                        hold += input.u8(inp++) shl bits
                        bits += 8
                    }
                    len += hold and ((1 shl op) - 1)
                    hold = hold ushr op
                    bits -= op
                }
                if (bits < 15) {
                    hold += input.u8(inp++) shl bits
                    bits += 8
                    hold += input.u8(inp++) shl bits
                    bits += 8
                }
                here = dcode[hold and dmask]

                dodist@ while (true) {
                    op = here ushr 24
                    hold = hold ushr op
                    bits -= op
                    op = (here ushr 16) and 0xff

                    if ((op and 16) != 0) {
                        var dist = here and 0xffff
                        op = op and 15
                        if (bits < op) {
                            hold += input.u8(inp++) shl bits
                            bits += 8
                            if (bits < op) {
                                hold += input.u8(inp++) shl bits
                                bits += 8
                            }
                        }
                        dist += hold and ((1 shl op) - 1)
                        hold = hold ushr op
                        bits -= op

                        op = out - beg
                        if (dist > op) {
                            // Part of the match comes from the window.
                            op = dist - op
                            if (op > whave || sWindow == null) {
                                strm.msg = "invalid distance too far back"
                                state.mode = BAD
                                break@top
                            }
                            val win: ByteArray = sWindow
                            var from = 0
                            var fromSource: ByteArray = win
                            if (wnext == 0) {
                                from += wsize - op
                                if (op < len) {
                                    len -= op
                                    do {
                                        output[out++] = win[from++]
                                    } while (--op != 0)
                                    from = out - dist
                                    fromSource = output
                                }
                            } else if (wnext < op) {
                                // Wraps around the end of the window.
                                from += wsize + wnext - op
                                op -= wnext
                                if (op < len) {
                                    len -= op
                                    do {
                                        output[out++] = win[from++]
                                    } while (--op != 0)
                                    from = 0
                                    if (wnext < len) {
                                        op = wnext
                                        len -= op
                                        do {
                                            output[out++] = win[from++]
                                        } while (--op != 0)
                                        from = out - dist
                                        fromSource = output
                                    }
                                }
                            } else {
                                from += wnext - op
                                if (op < len) {
                                    len -= op
                                    do {
                                        output[out++] = win[from++]
                                    } while (--op != 0)
                                    from = out - dist
                                    fromSource = output
                                }
                            }
                            while (len > 2) {
                                output[out++] = fromSource[from++]
                                output[out++] = fromSource[from++]
                                output[out++] = fromSource[from++]
                                len -= 3
                            }
                            if (len != 0) {
                                output[out++] = fromSource[from++]
                                if (len > 1) output[out++] = fromSource[from++]
                            }
                        } else {
                            var from = out - dist
                            do {
                                output[out++] = output[from++]
                                output[out++] = output[from++]
                                output[out++] = output[from++]
                                len -= 3
                            } while (len > 2)
                            if (len != 0) {
                                output[out++] = output[from++]
                                if (len > 1) output[out++] = output[from++]
                            }
                        }
                    } else if ((op and 64) == 0) {
                        // Second-level distance code.
                        here = dcode[(here and 0xffff) + (hold and ((1 shl op) - 1))]
                        continue@dodist
                    } else {
                        strm.msg = "invalid distance code"
                        state.mode = BAD
                        break@top
                    }
                    break@dodist
                }
            } else if ((op and 64) == 0) {
                // Second-level length code.
                here = lcode[(here and 0xffff) + (hold and ((1 shl op) - 1))]
                continue@dolen
            } else if ((op and 32) != 0) {
                state.mode = TYPE
                break@top
            } else {
                strm.msg = "invalid literal/length code"
                state.mode = BAD
                break@top
            }
            break@dolen
        }
    } while (inp < last && out < end)

    // Return unused bytes; bits are only ever loaded a whole byte at a time.
    val len = bits shr 3
    inp -= len
    bits -= len shl 3
    hold = hold and ((1 shl bits) - 1)

    strm.nextIn = inp
    strm.nextOut = out
    strm.availIn = if (inp < last) 5 + (last - inp) else 5 - (inp - last)
    strm.availOut = if (out < end) 257 + (end - out) else 257 - (out - end)
    state.hold = hold
    state.bits = bits
}

/** Returns a zlib status code: Z_OK, Z_STREAM_END, Z_NEED_DICT, Z_DATA_ERROR or Z_BUF_ERROR. */
internal fun inflate(state: InflateState): Int {
    val strm = state.strm
    if (state.mode == TYPE) state.mode = TYPEDO

    var put = strm.nextOut
    val output = strm.output
    var left = strm.availOut
    var next = strm.nextIn
    val input = strm.input
    var have = strm.availIn
    var hold = state.hold
    var bits = state.bits

    val inStart = have
    var outStart = left
    var ret = Z_OK

    infLeave@ while (true) {
        when (state.mode) {
            HEAD -> {
                if (state.wrap == 0) {
                    state.mode = TYPEDO
                    continue@infLeave
                }
                while (bits < 16) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                if ((state.wrap and 2) != 0 && hold == 0x8b1f) {
                    // gzip header
                    state.check = crc32(0, byteArrayOf(hold.toByte(), (hold ushr 8).toByte()), 2, 0)
                    hold = 0
                    bits = 0
                    state.mode = FLAGS
                    continue@infLeave
                }
                if ((state.wrap and 1) == 0 || ((((hold and 0xff) shl 8) + (hold shr 8)) % 31) != 0) {
                    strm.msg = "incorrect header check"
                    state.mode = BAD
                    continue@infLeave
                }
                if ((hold and 15) != 8) {
                    strm.msg = "unknown compression method"
                    state.mode = BAD
                    continue@infLeave
                }
                hold = hold ushr 4
                bits -= 4
                val len = (hold and 15) + 8
                if (len > state.wbits) {
                    strm.msg = "invalid window size"
                    state.mode = BAD
                    continue@infLeave
                }
                state.flags = 0
                strm.adler = 1
                state.check = 1
                state.mode = if ((hold and 0x200) != 0) DICTID else TYPE
                hold = 0
                bits = 0
            }
            FLAGS -> {
                while (bits < 16) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                state.flags = hold
                if ((state.flags and 0xff) != 8) {
                    strm.msg = "unknown compression method"
                    state.mode = BAD
                    continue@infLeave
                }
                if ((state.flags and 0xe000) != 0) {
                    strm.msg = "unknown header flags set"
                    state.mode = BAD
                    continue@infLeave
                }
                if ((state.flags and 0x200) != 0 && (state.wrap and 4) != 0) {
                    state.check = crc32(state.check, byteArrayOf(hold.toByte(), (hold ushr 8).toByte()), 2, 0)
                }
                hold = 0
                bits = 0
                state.mode = TIME
            }
            TIME -> {
                while (bits < 32) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                if ((state.flags and 0x200) != 0 && (state.wrap and 4) != 0) {
                    val hbuf = byteArrayOf(
                        hold.toByte(), (hold ushr 8).toByte(), (hold ushr 16).toByte(), (hold ushr 24).toByte(),
                    )
                    state.check = crc32(state.check, hbuf, 4, 0)
                }
                hold = 0
                bits = 0
                state.mode = OS
            }
            OS -> {
                while (bits < 16) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                if ((state.flags and 0x200) != 0 && (state.wrap and 4) != 0) {
                    state.check = crc32(state.check, byteArrayOf(hold.toByte(), (hold ushr 8).toByte()), 2, 0)
                }
                hold = 0
                bits = 0
                state.mode = EXLEN
            }
            EXLEN -> {
                if ((state.flags and 0x400) != 0) {
                    while (bits < 16) {
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    state.length = hold
                    if ((state.flags and 0x200) != 0 && (state.wrap and 4) != 0) {
                        state.check = crc32(state.check, byteArrayOf(hold.toByte(), (hold ushr 8).toByte()), 2, 0)
                    }
                    hold = 0
                    bits = 0
                }
                state.mode = EXTRA
            }
            EXTRA -> {
                if ((state.flags and 0x400) != 0) {
                    var copy = state.length
                    if (copy > have) copy = have
                    if (copy != 0) {
                        if ((state.flags and 0x200) != 0 && (state.wrap and 4) != 0) {
                            state.check = crc32(state.check, input, copy, next)
                        }
                        have -= copy
                        next += copy
                        state.length -= copy
                    }
                    if (state.length != 0) break@infLeave
                }
                state.length = 0
                state.mode = NAME
            }
            NAME, COMMENT -> {
                val flag = if (state.mode == NAME) 0x800 else 0x1000
                if ((state.flags and flag) != 0) {
                    if (have == 0) break@infLeave
                    var copy = 0
                    var b: Int
                    do {
                        b = input.u8(next + copy++)
                    } while (b != 0 && copy < have)
                    if ((state.flags and 0x200) != 0 && (state.wrap and 4) != 0) {
                        state.check = crc32(state.check, input, copy, next)
                    }
                    have -= copy
                    next += copy
                    if (b != 0) break@infLeave
                }
                state.length = 0
                state.mode = if (state.mode == NAME) COMMENT else HCRC
            }
            HCRC -> {
                if ((state.flags and 0x200) != 0) {
                    while (bits < 16) {
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    if ((state.wrap and 4) != 0 && hold != (state.check and 0xffff)) {
                        strm.msg = "header crc mismatch"
                        state.mode = BAD
                        continue@infLeave
                    }
                    hold = 0
                    bits = 0
                }
                strm.adler = 0
                state.check = 0
                state.mode = TYPE
            }
            DICTID -> {
                while (bits < 32) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                state.check = zswap32(hold)
                strm.adler = state.check
                hold = 0
                bits = 0
                state.mode = DICT
            }
            DICT -> {
                // Preset dictionaries are not supported.
                strm.nextOut = put
                strm.availOut = left
                strm.nextIn = next
                strm.availIn = have
                state.hold = hold
                state.bits = bits
                return Z_NEED_DICT
            }
            TYPE, TYPEDO -> {
                if (state.last) {
                    hold = hold ushr (bits and 7)
                    bits -= bits and 7
                    state.mode = CHECK
                    continue@infLeave
                }
                while (bits < 3) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                state.last = (hold and 1) != 0
                hold = hold ushr 1
                bits -= 1

                when (hold and 3) {
                    0 -> state.mode = STORED
                    1 -> {
                        fixedTables(state)
                        state.mode = LEN_
                    }
                    2 -> state.mode = TABLE
                    else -> {
                        strm.msg = "invalid block type"
                        state.mode = BAD
                    }
                }
                hold = hold ushr 2
                bits -= 2
            }
            STORED -> {
                hold = hold ushr (bits and 7)
                bits -= bits and 7
                while (bits < 32) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                if ((hold and 0xffff) != ((hold ushr 16) xor 0xffff)) {
                    strm.msg = "invalid stored block lengths"
                    state.mode = BAD
                    continue@infLeave
                }
                state.length = hold and 0xffff
                hold = 0
                bits = 0
                state.mode = COPY_
            }
            COPY_ -> state.mode = COPY
            COPY -> {
                var copy = state.length
                if (copy != 0) {
                    if (copy > have) copy = have
                    if (copy > left) copy = left
                    if (copy == 0) break@infLeave
                    input.copyInto(output, put, next, next + copy)
                    have -= copy
                    next += copy
                    left -= copy
                    put += copy
                    state.length -= copy
                    continue@infLeave
                }
                state.mode = TYPE
            }
            TABLE -> {
                while (bits < 14) {
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                state.nlen = (hold and 0x1f) + 257
                hold = hold ushr 5
                bits -= 5
                state.ndist = (hold and 0x1f) + 1
                hold = hold ushr 5
                bits -= 5
                state.ncode = (hold and 0x0f) + 4
                hold = hold ushr 4
                bits -= 4
                if (state.nlen > 286 || state.ndist > 30) {
                    strm.msg = "too many length or distance symbols"
                    state.mode = BAD
                    continue@infLeave
                }
                state.have = 0
                state.mode = LENLENS
            }
            LENLENS -> {
                while (state.have < state.ncode) {
                    while (bits < 3) {
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    state.lens[ORDER[state.have++]] = hold and 7
                    hold = hold ushr 3
                    bits -= 3
                }
                while (state.have < 19) state.lens[ORDER[state.have++]] = 0

                state.lencode = state.lendyn
                val root = inflateTable(CODES, state.lens, 0, 19, state.lencode, 0, state.work, 7)
                if (root < 0) {
                    strm.msg = "invalid code lengths set"
                    state.mode = BAD
                    continue@infLeave
                }
                state.lenbits = root
                state.have = 0
                state.mode = CODELENS
            }
            CODELENS -> {
                codeLens@ while (state.have < state.nlen + state.ndist) {
                    var here: Int
                    var hereBits: Int
                    var hereVal: Int
                    while (true) {
                        here = state.lencode[hold and ((1 shl state.lenbits) - 1)]
                        hereBits = here ushr 24
                        hereVal = here and 0xffff
                        if (hereBits <= bits) break
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    if (hereVal < 16) {
                        hold = hold ushr hereBits
                        bits -= hereBits
                        state.lens[state.have++] = hereVal
                    } else {
                        val len: Int
                        var copy: Int
                        if (hereVal == 16) {
                            val n = hereBits + 2
                            while (bits < n) {
                                if (have == 0) break@infLeave
                                have--
                                hold += input.u8(next++) shl bits
                                bits += 8
                            }
                            hold = hold ushr hereBits
                            bits -= hereBits
                            if (state.have == 0) {
                                strm.msg = "invalid bit length repeat"
                                state.mode = BAD
                                break@codeLens
                            }
                            len = state.lens[state.have - 1]
                            copy = 3 + (hold and 3)
                            hold = hold ushr 2
                            bits -= 2
                        } else if (hereVal == 17) {
                            val n = hereBits + 3
                            while (bits < n) {
                                if (have == 0) break@infLeave
                                have--
                                hold += input.u8(next++) shl bits
                                bits += 8
                            }
                            hold = hold ushr hereBits
                            bits -= hereBits
                            len = 0
                            copy = 3 + (hold and 7)
                            hold = hold ushr 3
                            bits -= 3
                        } else {
                            val n = hereBits + 7
                            while (bits < n) {
                                if (have == 0) break@infLeave
                                have--
                                hold += input.u8(next++) shl bits
                                bits += 8
                            }
                            hold = hold ushr hereBits
                            bits -= hereBits
                            len = 0
                            copy = 11 + (hold and 0x7f)
                            hold = hold ushr 7
                            bits -= 7
                        }
                        if (state.have + copy > state.nlen + state.ndist) {
                            strm.msg = "invalid bit length repeat"
                            state.mode = BAD
                            break@codeLens
                        }
                        while (copy-- != 0) state.lens[state.have++] = len
                    }
                }

                if (state.mode == BAD) continue@infLeave

                if (state.lens[256] == 0) {
                    strm.msg = "invalid code -- missing end-of-block"
                    state.mode = BAD
                    continue@infLeave
                }

                state.lencode = state.lendyn
                var root = inflateTable(LENS, state.lens, 0, state.nlen, state.lencode, 0, state.work, 9)
                if (root < 0) {
                    strm.msg = "invalid literal/lengths set"
                    state.mode = BAD
                    continue@infLeave
                }
                state.lenbits = root

                state.distcode = state.distdyn
                root = inflateTable(DISTS, state.lens, state.nlen, state.ndist, state.distcode, 0, state.work, 6)
                if (root < 0) {
                    strm.msg = "invalid distances set"
                    state.mode = BAD
                    continue@infLeave
                }
                state.distbits = root
                state.mode = LEN_
            }
            LEN_ -> state.mode = LEN
            LEN -> {
                if (have >= 6 && left >= 258) {
                    strm.nextOut = put
                    strm.availOut = left
                    strm.nextIn = next
                    strm.availIn = have
                    state.hold = hold
                    state.bits = bits
                    inflateFast(state, outStart)
                    put = strm.nextOut
                    left = strm.availOut
                    next = strm.nextIn
                    have = strm.availIn
                    hold = state.hold
                    bits = state.bits
                    continue@infLeave
                }
                var here: Int
                var hereBits: Int
                var hereOp: Int
                var hereVal: Int
                while (true) {
                    here = state.lencode[hold and ((1 shl state.lenbits) - 1)]
                    hereBits = here ushr 24
                    hereOp = (here ushr 16) and 0xff
                    hereVal = here and 0xffff
                    if (hereBits <= bits) break
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                if (hereOp != 0 && (hereOp and 0xf0) == 0) {
                    val lastBits = hereBits
                    val lastOp = hereOp
                    val lastVal = hereVal
                    while (true) {
                        here = state.lencode[lastVal + ((hold and ((1 shl (lastBits + lastOp)) - 1)) shr lastBits)]
                        hereBits = here ushr 24
                        hereOp = (here ushr 16) and 0xff
                        hereVal = here and 0xffff
                        if (lastBits + hereBits <= bits) break
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    hold = hold ushr lastBits
                    bits -= lastBits
                }
                hold = hold ushr hereBits
                bits -= hereBits
                state.length = hereVal
                if (hereOp == 0) {
                    state.mode = LIT
                    continue@infLeave
                }
                if ((hereOp and 32) != 0) {
                    state.mode = TYPE
                    continue@infLeave
                }
                if ((hereOp and 64) != 0) {
                    strm.msg = "invalid literal/length code"
                    state.mode = BAD
                    continue@infLeave
                }
                state.extra = hereOp and 15
                state.mode = LENEXT
            }
            LENEXT -> {
                if (state.extra != 0) {
                    val n = state.extra
                    while (bits < n) {
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    state.length += hold and ((1 shl state.extra) - 1)
                    hold = hold ushr state.extra
                    bits -= state.extra
                }
                state.mode = DIST
            }
            DIST -> {
                var here: Int
                var hereBits: Int
                var hereOp: Int
                var hereVal: Int
                while (true) {
                    here = state.distcode[hold and ((1 shl state.distbits) - 1)]
                    hereBits = here ushr 24
                    hereOp = (here ushr 16) and 0xff
                    hereVal = here and 0xffff
                    if (hereBits <= bits) break
                    if (have == 0) break@infLeave
                    have--
                    hold += input.u8(next++) shl bits
                    bits += 8
                }
                if ((hereOp and 0xf0) == 0) {
                    val lastBits = hereBits
                    val lastOp = hereOp
                    val lastVal = hereVal
                    while (true) {
                        here = state.distcode[lastVal + ((hold and ((1 shl (lastBits + lastOp)) - 1)) shr lastBits)]
                        hereBits = here ushr 24
                        hereOp = (here ushr 16) and 0xff
                        hereVal = here and 0xffff
                        if (lastBits + hereBits <= bits) break
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    hold = hold ushr lastBits
                    bits -= lastBits
                }
                hold = hold ushr hereBits
                bits -= hereBits
                if ((hereOp and 64) != 0) {
                    strm.msg = "invalid distance code"
                    state.mode = BAD
                    continue@infLeave
                }
                state.offset = hereVal
                state.extra = hereOp and 15
                state.mode = DISTEXT
            }
            DISTEXT -> {
                if (state.extra != 0) {
                    val n = state.extra
                    while (bits < n) {
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    state.offset += hold and ((1 shl state.extra) - 1)
                    hold = hold ushr state.extra
                    bits -= state.extra
                }
                state.mode = MATCH
            }
            MATCH -> {
                if (left == 0) break@infLeave
                var copy = outStart - left
                val fromSource: ByteArray
                var from: Int
                if (state.offset > copy) {
                    // Copy from the window.
                    copy = state.offset - copy
                    val window = state.window
                    if (copy > state.whave || window == null) {
                        strm.msg = "invalid distance too far back"
                        state.mode = BAD
                        continue@infLeave
                    }
                    if (copy > state.wnext) {
                        copy -= state.wnext
                        from = state.wsize - copy
                    } else {
                        from = state.wnext - copy
                    }
                    if (copy > state.length) copy = state.length
                    fromSource = window
                } else {
                    fromSource = output
                    from = put - state.offset
                    copy = state.length
                }
                if (copy > left) copy = left
                left -= copy
                state.length -= copy
                do {
                    output[put++] = fromSource[from++]
                } while (--copy != 0)
                if (state.length == 0) state.mode = LEN
            }
            LIT -> {
                if (left == 0) break@infLeave
                output[put++] = state.length.toByte()
                left--
                state.mode = LEN
            }
            CHECK -> {
                if (state.wrap != 0) {
                    while (bits < 32) {
                        if (have == 0) break@infLeave
                        have--
                        hold = hold or (input.u8(next++) shl bits)
                        bits += 8
                    }
                    outStart -= left
                    state.total += outStart
                    if ((state.wrap and 4) != 0 && outStart != 0) {
                        state.check = updateCheck(state, output, outStart, put - outStart)
                        strm.adler = state.check
                    }
                    outStart = left
                    if ((state.wrap and 4) != 0 && (if (state.flags != 0) hold else zswap32(hold)) != state.check) {
                        strm.msg = "incorrect data check"
                        state.mode = BAD
                        continue@infLeave
                    }
                    hold = 0
                    bits = 0
                }
                state.mode = LENGTH
            }
            LENGTH -> {
                if (state.wrap != 0 && state.flags != 0) {
                    while (bits < 32) {
                        if (have == 0) break@infLeave
                        have--
                        hold += input.u8(next++) shl bits
                        bits += 8
                    }
                    if ((state.wrap and 4) != 0 && hold != state.total.toInt()) {
                        strm.msg = "incorrect length check"
                        state.mode = BAD
                        continue@infLeave
                    }
                    hold = 0
                    bits = 0
                }
                state.mode = DONE
            }
            DONE -> {
                ret = Z_STREAM_END
                break@infLeave
            }
            BAD -> {
                ret = Z_DATA_ERROR
                break@infLeave
            }
            else -> return Z_STREAM_ERROR
        }
    }

    strm.nextOut = put
    strm.availOut = left
    strm.nextIn = next
    strm.availIn = have
    state.hold = hold
    state.bits = bits

    // Keep the last window's worth of output for back-references in the next call.
    if (state.wsize != 0 || (outStart != strm.availOut && state.mode < BAD)) {
        updateWindow(state, strm.output, strm.nextOut, outStart - strm.availOut)
    }
    val consumed = inStart - strm.availIn
    val produced = outStart - strm.availOut
    state.total += produced
    if ((state.wrap and 4) != 0 && produced != 0) {
        state.check = updateCheck(state, strm.output, produced, strm.nextOut - produced)
        strm.adler = state.check
    }
    if (consumed == 0 && produced == 0 && ret == Z_OK) ret = Z_BUF_ERROR
    return ret
}

private fun updateCheck(state: InflateState, buf: ByteArray, len: Int, pos: Int): Int =
    if (state.flags != 0) crc32(state.check, buf, len, pos) else adler32(state.check, buf, len, pos)
