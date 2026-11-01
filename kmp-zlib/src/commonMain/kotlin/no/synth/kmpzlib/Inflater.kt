package no.synth.kmpzlib

import kotlin.jvm.JvmOverloads

/** The compressed data is corrupt, truncated mid-header, or needs a preset dictionary. */
public class ZlibDataException(message: String) : Exception(message)

/**
 * Streaming DEFLATE decompressor, a pure-Kotlin port of pako's zlib port.
 *
 * Call [inflate] with any slice of input and room for output. It stops at the end of
 * the compressed stream, so [CodecResult.bytesConsumed] leaves any trailing bytes (a
 * ZIP data descriptor, the next gzip member) unread. zlib and gzip check values are
 * verified when the stream end is reached. Preset dictionaries are not supported.
 *
 * Not thread-safe; use one instance per stream.
 */
public class Inflater @JvmOverloads constructor(public val format: ZlibFormat = ZlibFormat.ZLIB) {
    private val strm = ZStream()
    private val state: InflateState = inflateInit2(strm, format.windowBits())

    /** True once the end of the compressed stream has been reached. */
    public var isFinished: Boolean = false
        private set

    private var failure: String? = null

    /**
     * Returns zero progress, not an error, when more input or output room is needed.
     *
     * @throws ZlibDataException if the data is invalid; the inflater is then unusable
     *   until [reset]
     */
    @Throws(ZlibDataException::class, IndexOutOfBoundsException::class)
    public fun inflate(
        input: ByteArray, inputOffset: Int, inputLength: Int,
        output: ByteArray, outputOffset: Int, outputLength: Int,
    ): CodecResult {
        checkRange(input, inputOffset, inputLength)
        checkRange(output, outputOffset, outputLength)
        failure?.let { throw ZlibDataException(it) }
        if (isFinished) return CodecResult(0, 0, true)

        strm.input = input
        strm.nextIn = inputOffset
        strm.availIn = inputLength
        strm.output = output
        strm.nextOut = outputOffset
        strm.availOut = outputLength

        val ret = try {
            inflate(state)
        } finally {
            strm.input = EMPTY
            strm.output = EMPTY
        }
        when (ret) {
            Z_OK, Z_BUF_ERROR -> {}
            Z_STREAM_END -> isFinished = true
            Z_NEED_DICT -> fail("preset dictionary not supported")
            Z_DATA_ERROR -> fail(strm.msg.ifEmpty { "invalid data" })
            else -> throw IllegalStateException("inflate failed: $ret ${strm.msg}")
        }
        return CodecResult(inputLength - strm.availIn, outputLength - strm.availOut, isFinished)
    }

    private fun fail(message: String): Nothing {
        failure = message
        throw ZlibDataException(message)
    }

    /** Starts a new stream with the same format, e.g. the next member of a gzip file. */
    public fun reset() {
        inflateReset(state)
        isFinished = false
        failure = null
    }
}
