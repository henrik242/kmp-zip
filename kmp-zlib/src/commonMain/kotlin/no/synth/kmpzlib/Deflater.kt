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
 * `finish = true` until [CodecResult.streamEnd] is true, passing no further input.
 *
 * Not thread-safe; use one instance per stream.
 *
 * @param level 0-9, or [DEFAULT_COMPRESSION] (6)
 */
public class Deflater @JvmOverloads constructor(
    level: Int = DEFAULT_COMPRESSION,
    public val format: ZlibFormat = ZlibFormat.ZLIB,
) {
    private val strm = ZStream()
    private val state: DeflateState

    init {
        require(level in -1..9) { "Invalid compression level: $level" }
        state = deflateInit2(strm, level, format.windowBits(), 8)
    }

    /** True once the trailer has been fully written. */
    public var isFinished: Boolean = false
        private set

    private var finishing = false

    @JvmOverloads
    @Throws(IllegalStateException::class, IndexOutOfBoundsException::class)
    public fun deflate(
        input: ByteArray, inputOffset: Int, inputLength: Int,
        output: ByteArray, outputOffset: Int, outputLength: Int,
        finish: Boolean = false,
    ): CodecResult {
        checkRange(input, inputOffset, inputLength)
        checkRange(output, outputOffset, outputLength)
        if (finishing && (inputLength > 0 || !finish)) {
            throw IllegalStateException("Deflater is finishing; call with finish = true and no input")
        }
        if (finish) finishing = true

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
        return CodecResult(inputLength - strm.availIn, outputLength - strm.availOut, isFinished)
    }

    /** Starts a new stream with the same settings. */
    public fun reset() {
        deflateReset(state)
        isFinished = false
        finishing = false
    }

    public companion object {
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
