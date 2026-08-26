package no.synth.kmpzip.zip

// pako's Deflate.push() always consumes the entire input synchronously and
// appends output to a JS-side `chunks` array; unlike zlib it has no avail_out
// backpressure. We push only when the caller's output buffer has room (either
// new input or a final flush is pending), drain pako's chunks after each push,
// and report `bytesConsumed = inputLen` on push or 0 when only draining.
internal actual class PlatformDeflater actual constructor() {
    private var deflater: Deflate? = null
    private val drain = PakoOutputDrain()

    actual fun init(level: Int, nowrap: Boolean, gzip: Boolean) {
        // gzip wins over nowrap when both are set — matches the native
        // resolveWindowBits convention so behaviour is identical across targets.
        val opts = pakoDeflateOptions(
            level = level,
            raw = nowrap && !gzip,
            gzip = gzip,
            chunkSize = 4096,
        )
        deflater = Deflate(opts)
        drain.reset()
    }

    actual fun deflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
        finish: Boolean,
    ): DeflateResult {
        val d = deflater ?: throw IllegalStateException("Deflater not initialized")

        var produced = drain.draw(d.chunks, d.result, output, outputOffset, outputLen)
        var consumed = 0

        val canPush = produced < outputLen && !d.ended && (inputLen > 0 || finish)
        if (canPush) {
            val chunk = byteArrayToUint8Array(input, inputOffset, inputLen)
            val flushMode = if (finish) Z_FINISH else Z_NO_FLUSH
            val ok = d.push(chunk, flushMode)
            if (!ok) {
                throw IllegalStateException("pako deflate failed: err=${d.err}, msg=${d.msg}")
            }
            consumed = inputLen
            produced += drain.draw(d.chunks, d.result, output, outputOffset + produced, outputLen - produced)
        }

        return DeflateResult(consumed, produced, drain.isStreamEnd(d.result))
    }

    actual fun end() {
        deflater = null
        drain.reset()
    }

    actual val isFinished: Boolean
        get() = deflater?.let { drain.isStreamEnd(it.result) } ?: true
}
