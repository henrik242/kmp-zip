package no.synth.kmpzip.zip

import kotlinx.cinterop.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
internal class Deflater {
    private val stream = nativeHeap.alloc<z_stream>()
    private var finished = false
    private var initialized = false

    fun init(level: Int = Z_DEFAULT_COMPRESSION, wbits: Int = -MAX_WBITS) {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.avail_in = 0u
        stream.next_in = null
        val ret = deflateInit2(stream.ptr, level, Z_DEFLATED, wbits, 8, Z_DEFAULT_STRATEGY)
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
                stream.next_in = toUBytePointer(inPin, inputOffset)
            } else {
                stream.next_in = null
            }
            stream.avail_in = inputLen.toUInt()
            stream.next_out = toUBytePointer(outPin, outputOffset)
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
