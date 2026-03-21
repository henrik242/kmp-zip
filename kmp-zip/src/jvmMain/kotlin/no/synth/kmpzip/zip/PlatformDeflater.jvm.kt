package no.synth.kmpzip.zip

internal actual class PlatformDeflater actual constructor() {
    private var jvmDeflater: java.util.zip.Deflater? = null
    private var finishCalled = false

    actual fun init(level: Int, nowrap: Boolean, gzip: Boolean) {
        // JVM's Deflater only supports nowrap (raw deflate) or default (zlib wrapping).
        // For GZIP, we use GZIPOutputStream on JVM, but to support the same API,
        // we use nowrap=false and handle the gzip header externally.
        // In practice, GZIP on JVM uses java.util.zip.GZIPOutputStream directly.
        jvmDeflater = java.util.zip.Deflater(level, nowrap && !gzip)
        finishCalled = false
    }

    actual fun deflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
        finish: Boolean,
    ): DeflateResult {
        val def = jvmDeflater ?: throw IllegalStateException("Deflater not initialized")

        val bytesReadBefore = def.bytesRead

        if (inputLen > 0) {
            def.setInput(input, inputOffset, inputLen)
        }
        if (finish && !finishCalled) {
            def.finish()
            finishCalled = true
        }

        val produced = def.deflate(output, outputOffset, outputLen)
        val consumed = (def.bytesRead - bytesReadBefore).toInt()

        return DeflateResult(consumed, produced, def.finished())
    }

    actual fun end() {
        jvmDeflater?.end()
        jvmDeflater = null
    }

    actual val isFinished: Boolean
        get() = jvmDeflater?.finished() ?: true
}
