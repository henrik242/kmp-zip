package no.synth.kmpzip.zip

// Same drain-then-push design as PlatformDeflater.wasmJs.kt — see that file
// for rationale. Stream-end is observed via pako's `ended` flag.
internal actual class PlatformInflater actual constructor() {
    private var inflater: Inflate? = null
    private val drain = PakoOutputDrain()
    private var lastNowrap = false
    private var lastGzip = false

    actual fun init(nowrap: Boolean, gzip: Boolean) {
        val opts = pakoInflateOptions(
            raw = nowrap && !gzip,
            gzip = gzip,
            chunkSize = 4096,
        )
        inflater = Inflate(opts)
        drain.reset()
        lastNowrap = nowrap
        lastGzip = gzip
    }

    actual fun reset() {
        // pako's Inflate has no reset; recreate with the original options.
        if (inflater == null) throw IllegalStateException("Inflater not initialized")
        init(lastNowrap, lastGzip)
    }

    actual fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult {
        val inf = inflater ?: throw IllegalStateException("Inflater not initialized")
        if (drain.isStreamEnd(inf.result)) return InflateResult(0, 0, true)

        var produced = drain.draw(inf.chunks, inf.result, output, outputOffset, outputLen)
        var consumed = 0

        if (produced < outputLen && inputLen > 0 && !inf.ended) {
            val chunk = byteArrayToUint8Array(input, inputOffset, inputLen)
            val ok = inf.push(chunk, Z_NO_FLUSH)
            if (!ok) {
                // Defensive: pako 2.x's Inflate.push() doesn't currently surface
                // Z_BUF_ERROR through this return value, but the native impl
                // tolerates it and we keep parity in case pako's behaviour changes.
                if (inf.err != Z_BUF_ERROR) {
                    throw IllegalStateException("pako inflate failed: err=${inf.err}, msg=${inf.msg}")
                }
            }
            // Once Z_STREAM_END is reached, pako stops reading mid-buffer; the
            // unread tail is the start of the ZIP data descriptor and must be
            // returned to the caller.
            consumed = inputLen - strmAvailIn(inf.strm)
            produced += drain.draw(inf.chunks, inf.result, output, outputOffset + produced, outputLen - produced)
        }

        return InflateResult(consumed, produced, drain.isStreamEnd(inf.result))
    }

    actual fun end() {
        inflater = null
        drain.reset()
    }

    actual val isFinished: Boolean
        get() = inflater?.let { drain.isStreamEnd(it.result) } ?: true
}
