package no.synth.kmpzlib

import kotlin.jvm.JvmOverloads

/** The framing around the DEFLATE data. */
public enum class ZlibFormat {
    /** Raw DEFLATE (RFC 1951) with no header or trailer, as used inside ZIP entries. */
    RAW,

    /** zlib (RFC 1950): 2-byte header and Adler-32 trailer. */
    ZLIB,

    /** gzip (RFC 1952): 10-byte header and CRC-32 + size trailer. */
    GZIP,
}

/** Outcome of one [Deflater.deflate] or [Inflater.inflate] call. */
public class CodecResult(
    public val bytesConsumed: Int,
    public val bytesProduced: Int,
    public val streamEnd: Boolean,
) {
    override fun toString(): String =
        "CodecResult(bytesConsumed=$bytesConsumed, bytesProduced=$bytesProduced, streamEnd=$streamEnd)"
}

/**
 * Streaming DEFLATE compressor, a pure-Kotlin port of pako's zlib port.
 *
 * Call [deflate] with any slice of input and room for output; it consumes what it can
 * and reports how far it got. Output can be held back between calls, so after the input
 * is used up keep calling while the output buffer fills. To end the stream, call with
 * `finish = true` until [CodecResult.streamEnd] is true. If such a call leaves input
 * unread, pass all of the rest in the next call: a finishing deflater treats the input it
 * is given as the last, so a part would end the stream early. Once all input is consumed,
 * pass no more. Only the length of that remainder is checked: passing the same bytes is
 * up to the caller.
 *
 * ```
 * val deflater = Deflater(6, ZlibFormat.GZIP)
 * val buf = ByteArray(16 * 1024)
 * var off = 0
 * do {
 *     val r = deflater.deflate(data, off, data.size - off, buf, 0, buf.size, finish = true)
 *     off += r.bytesConsumed
 *     out.write(buf, 0, r.bytesProduced)
 * } while (!r.streamEnd)
 * ```
 *
 * Not thread-safe; use one instance per stream.
 *
 * @param level 0-9, or [DEFAULT_COMPRESSION] (6)
 * @param format the framing to write around the DEFLATE data
 */
public class Deflater @JvmOverloads constructor(
    public val level: Int = DEFAULT_COMPRESSION,
    public val format: ZlibFormat = ZlibFormat.ZLIB,
) {
    private val strm = ZStream()
    private val state: DeflateState

    init {
        require(level in -1..9) { "Invalid compression level: $level" }
        state = deflateInit(
            strm, level,
            wrap = when (format) {
                ZlibFormat.RAW -> 0
                ZlibFormat.ZLIB -> 1
                ZlibFormat.GZIP -> 2
            },
        )
    }

    /** True once the trailer has been fully written. */
    public var isFinished: Boolean = false
        private set

    // Input the finish calls so far have left unread, or -1 before the first finish call.
    // A finishing deflater takes exactly that many bytes: less would end the stream without
    // the rest, more is new data after the end of the stream was requested. The bytes
    // themselves are not checked; that would mean copying the remainder on every call.
    private var unreadAtFinish = -1

    /**
     * Compresses from `input[inputOffset, inputOffset + inputLength)` into
     * `output[outputOffset, outputOffset + outputLength)`.
     *
     * @param finish end the stream once this input is compressed. After the first call
     *   with `finish = true`, every call must pass `finish = true` and exactly the input
     *   earlier calls left unread. Only its length is checked; the caller must pass the
     *   same bytes.
     * @throws IllegalStateException if a call after the first with `finish = true` passes
     *   `finish = false`, or input of another length than the unread remainder
     */
    @JvmOverloads
    @Throws(IllegalStateException::class, IndexOutOfBoundsException::class)
    public fun deflate(
        input: ByteArray, inputOffset: Int, inputLength: Int,
        output: ByteArray, outputOffset: Int, outputLength: Int,
        finish: Boolean = false,
    ): CodecResult {
        checkRange(input, inputOffset, inputLength)
        checkRange(output, outputOffset, outputLength)
        if (unreadAtFinish >= 0) {
            check(finish) { "Deflater is finishing; every later call must pass finish = true" }
            check(inputLength == unreadAtFinish) {
                "Deflater is finishing; pass exactly the $unreadAtFinish input bytes earlier " +
                    "calls left unread, not $inputLength"
            }
        }

        strm.input = input
        strm.nextIn = inputOffset
        strm.availIn = inputLength
        strm.output = output
        strm.nextOut = outputOffset
        strm.availOut = outputLength

        val ret = try {
            deflate(state, if (finish) Z_FINISH else Z_NO_FLUSH)
        } finally {
            strm.input = EMPTY
            strm.output = EMPTY
        }
        when (ret) {
            Z_OK, Z_BUF_ERROR -> {}
            Z_STREAM_END -> isFinished = true
            else -> throw IllegalStateException("deflate failed: $ret ${strm.msg}")
        }
        val consumed = inputLength - strm.availIn
        if (finish) {
            unreadAtFinish = inputLength - consumed
        }
        return CodecResult(consumed, outputLength - strm.availOut, isFinished)
    }

    /** Starts a new stream with the same settings. */
    public fun reset() {
        deflateReset(state)
        isFinished = false
        unreadAtFinish = -1
    }

    public companion object {
        /** zlib's default level, 6: a good balance of speed and size. */
        public const val DEFAULT_COMPRESSION: Int = -1
    }
}

internal fun ZlibFormat.windowBits(): Int = when (this) {
    ZlibFormat.RAW -> -15
    ZlibFormat.ZLIB -> 15
    ZlibFormat.GZIP -> 15 + 16
}

internal fun checkRange(b: ByteArray, off: Int, len: Int) {
    if (off < 0 || len < 0 || len > b.size - off) {
        throw IndexOutOfBoundsException("off=$off, len=$len, size=${b.size}")
    }
}
