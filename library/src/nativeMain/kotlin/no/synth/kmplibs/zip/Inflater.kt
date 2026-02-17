package no.synth.kmplibs.zip

import kotlinx.cinterop.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
internal class Inflater {
    private val stream = nativeHeap.alloc<z_stream>()
    private var finished = false
    private var initialized = false

    fun init() {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.avail_in = 0u
        stream.next_in = null
        // -MAX_WBITS for raw deflate (no zlib/gzip header)
        val ret = inflateInit2(stream.ptr, -MAX_WBITS)
        if (ret != Z_OK) throw Exception("inflateInit2 failed: $ret")
        initialized = true
    }

    fun inflate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int, outputLen: Int): InflateResult {
        if (finished || !initialized) return InflateResult(0, 0, true)

        return memScoped {
            val inPin = input.pin()
            val outPin = output.pin()
            try {
                stream.next_in = (inPin.addressOf(inputOffset) as CPointer<UByteVar>)
                stream.avail_in = inputLen.toUInt()
                stream.next_out = (outPin.addressOf(outputOffset) as CPointer<UByteVar>)
                stream.avail_out = outputLen.toUInt()

                val ret = inflate(stream.ptr, Z_NO_FLUSH)
                if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                    throw Exception("inflate failed: $ret")
                }

                val bytesConsumed = inputLen - stream.avail_in.toInt()
                val bytesProduced = outputLen - stream.avail_out.toInt()
                finished = ret == Z_STREAM_END

                InflateResult(bytesConsumed, bytesProduced, finished)
            } finally {
                inPin.unpin()
                outPin.unpin()
            }
        }
    }

    fun end() {
        if (initialized) {
            inflateEnd(stream.ptr)
            initialized = false
        }
        nativeHeap.free(stream)
    }

    val isFinished: Boolean get() = finished
}

internal data class InflateResult(
    val bytesConsumed: Int,
    val bytesProduced: Int,
    val streamEnd: Boolean,
)
