package no.synth.kmpio.zip

import kotlinx.cinterop.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
internal class Deflater {
    private val stream = nativeHeap.alloc<z_stream>()
    private var finished = false
    private var initialized = false

    fun init(level: Int = Z_DEFAULT_COMPRESSION) {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.avail_in = 0u
        stream.next_in = null
        // -MAX_WBITS for raw deflate (no zlib/gzip header)
        val ret = deflateInit2(stream.ptr, level, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY)
        if (ret != Z_OK) throw Exception("deflateInit2 failed: $ret")
        initialized = true
    }

    fun deflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
        flush: Int = Z_NO_FLUSH,
    ): DeflateResult {
        if (!initialized) throw Exception("Deflater not initialized")

        val outPin = output.pin()
        val inPin = if (inputLen > 0) input.pin() else null
        return try {
            if (inPin != null) {
                stream.next_in = (inPin.addressOf(inputOffset) as CPointer<UByteVar>)
            } else {
                stream.next_in = null
            }
            stream.avail_in = inputLen.toUInt()
            stream.next_out = (outPin.addressOf(outputOffset) as CPointer<UByteVar>)
            stream.avail_out = outputLen.toUInt()

            val ret = deflate(stream.ptr, flush)
            if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                throw Exception("deflate failed: $ret")
            }

            val bytesConsumed = inputLen - stream.avail_in.toInt()
            val bytesProduced = outputLen - stream.avail_out.toInt()
            finished = ret == Z_STREAM_END

            DeflateResult(bytesConsumed, bytesProduced, finished)
        } finally {
            inPin?.unpin()
            outPin.unpin()
        }
    }

    fun end() {
        if (initialized) {
            deflateEnd(stream.ptr)
            initialized = false
        }
        nativeHeap.free(stream)
    }

    val isFinished: Boolean get() = finished
}

internal data class DeflateResult(
    val bytesConsumed: Int,
    val bytesProduced: Int,
    val streamEnd: Boolean,
)
