package no.synth.kmpzip.zip

import kotlinx.cinterop.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformInflater actual constructor() {
    private var stream: z_stream? = null
    private var finished = false

    actual fun init(nowrap: Boolean, gzip: Boolean) {
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
        val ret = inflateInit2(s.ptr, wbits)
        if (ret != Z_OK) {
            nativeHeap.free(s)
            throw IllegalStateException("inflateInit2 failed: $ret")
        }
        stream = s
        finished = false
    }

    actual fun reset() {
        val s = stream ?: throw IllegalStateException("Inflater not initialized")
        val ret = inflateReset(s.ptr)
        if (ret != Z_OK) throw IllegalStateException("inflateReset failed: $ret")
        finished = false
    }

    actual fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult {
        val s = stream ?: throw IllegalStateException("Inflater not initialized")
        if (finished) return InflateResult(0, 0, true)

        return withPinned(input, inputOffset, inputLen) { inPtr ->
            withPinned(output, outputOffset, outputLen) { outPtr ->
                s.next_in = inPtr
                s.avail_in = inputLen.toUInt()
                s.next_out = outPtr
                s.avail_out = outputLen.toUInt()

                val ret = inflate(s.ptr, Z_NO_FLUSH)
                if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                    // Mark finished so subsequent calls don't re-enter zlib in a
                    // corrupted state; the caller is expected to close() us.
                    finished = true
                    throw IllegalStateException("inflate failed: $ret")
                }

                val bytesConsumed = inputLen - s.avail_in.toInt()
                val bytesProduced = outputLen - s.avail_out.toInt()
                finished = ret == Z_STREAM_END

                InflateResult(bytesConsumed, bytesProduced, finished)
            }
        }
    }

    actual fun end() {
        stream?.let {
            inflateEnd(it.ptr)
            nativeHeap.free(it)
        }
        stream = null
    }

    actual val isFinished: Boolean get() = finished
}
