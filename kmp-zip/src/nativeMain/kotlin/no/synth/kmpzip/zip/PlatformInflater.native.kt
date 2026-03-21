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
        val wbits = when {
            gzip -> MAX_WBITS + 16
            nowrap -> -MAX_WBITS
            else -> MAX_WBITS
        }
        val ret = inflateInit2(s.ptr, wbits)
        if (ret != Z_OK) {
            nativeHeap.free(s)
            throw Exception("inflateInit2 failed: $ret")
        }
        stream = s
        finished = false
    }

    actual fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult {
        val s = stream ?: return InflateResult(0, 0, true)
        if (finished) return InflateResult(0, 0, true)

        val inPin = input.pin()
        val outPin = output.pin()
        return try {
            s.next_in = toUBytePointer(inPin, inputOffset)
            s.avail_in = inputLen.toUInt()
            s.next_out = toUBytePointer(outPin, outputOffset)
            s.avail_out = outputLen.toUInt()

            val ret = inflate(s.ptr, Z_NO_FLUSH)
            if (ret != Z_OK && ret != Z_STREAM_END && ret != Z_BUF_ERROR) {
                throw Exception("inflate failed: $ret")
            }

            val bytesConsumed = inputLen - s.avail_in.toInt()
            val bytesProduced = outputLen - s.avail_out.toInt()
            finished = ret == Z_STREAM_END

            InflateResult(bytesConsumed, bytesProduced, finished)
        } finally {
            inPin.unpin()
            outPin.unpin()
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
