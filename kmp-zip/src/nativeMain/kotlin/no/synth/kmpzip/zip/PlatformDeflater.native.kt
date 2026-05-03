package no.synth.kmpzip.zip

import kotlinx.cinterop.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformDeflater actual constructor() {
    private var stream: z_stream? = null
    private var finished = false

    actual fun init(level: Int, nowrap: Boolean, gzip: Boolean) {
        val s = nativeHeap.alloc<z_stream>()
        s.zalloc = null
        s.zfree = null
        s.opaque = null
        s.avail_in = 0u
        s.next_in = null
        // zlib's MAX_WBITS is defined as the macro 15. Inlined here because some K/Native
        // zlib cinterop bindings don't expose macro constants.
        val wbits = when {
            gzip -> 15 + 16
            nowrap -> -15
            else -> 15
        }
        val ret = deflateInit2(s.ptr, level, Z_DEFLATED, wbits, 8, Z_DEFAULT_STRATEGY)
        if (ret != Z_OK) {
            nativeHeap.free(s)
            throw IllegalStateException("deflateInit2 failed: $ret")
        }
        stream = s
        finished = false
    }

    actual fun deflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
        finish: Boolean,
    ): DeflateResult {
        val s = stream ?: throw IllegalStateException("Deflater not initialized")

        return withPinned(input, inputOffset, inputLen) { inPtr ->
            withPinned(output, outputOffset, outputLen) { outPtr ->
                s.next_in = inPtr
                s.avail_in = inputLen.toUInt()
                s.next_out = outPtr
                s.avail_out = outputLen.toUInt()

                val flush = if (finish) Z_FINISH else Z_NO_FLUSH
                val ret = deflate(s.ptr, flush)
                if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                    finished = true
                    throw IllegalStateException("deflate failed: $ret")
                }

                val bytesConsumed = inputLen - s.avail_in.toInt()
                val bytesProduced = outputLen - s.avail_out.toInt()
                finished = ret == Z_STREAM_END

                DeflateResult(bytesConsumed, bytesProduced, finished)
            }
        }
    }

    actual fun end() {
        stream?.let {
            deflateEnd(it.ptr)
            nativeHeap.free(it)
        }
        stream = null
    }

    actual val isFinished: Boolean get() = finished
}
