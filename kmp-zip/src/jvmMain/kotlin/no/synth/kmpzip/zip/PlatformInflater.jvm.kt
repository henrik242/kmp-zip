package no.synth.kmpzip.zip

internal actual class PlatformInflater actual constructor() {
    private var jvmInflater: java.util.zip.Inflater? = null

    actual fun init(nowrap: Boolean, gzip: Boolean) {
        jvmInflater = java.util.zip.Inflater(nowrap && !gzip)
    }

    actual fun reset() {
        jvmInflater?.reset()
    }

    actual fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult {
        val inf = jvmInflater ?: throw IllegalStateException("Inflater not initialized")
        if (inf.finished()) return InflateResult(0, 0, true)

        val bytesReadBefore = inf.bytesRead

        if (inputLen > 0) {
            inf.setInput(input, inputOffset, inputLen)
        }

        val produced = try {
            inf.inflate(output, outputOffset, outputLen)
        } catch (e: java.util.zip.DataFormatException) {
            // Bad compressed data. Map to the shared codec type so callers can tell a data
            // fault from a programmer error (bad args, uninitialized), which propagate as-is.
            // Keep the original as cause so the codec type and stack trace survive.
            throw CodecException(e.message ?: "inflate failed: bad compressed data", e)
        }
        val consumed = (inf.bytesRead - bytesReadBefore).toInt()

        return InflateResult(consumed, produced, inf.finished())
    }

    actual fun end() {
        jvmInflater?.end()
        jvmInflater = null
    }

    actual val isFinished: Boolean
        get() = jvmInflater?.finished() ?: true
}
