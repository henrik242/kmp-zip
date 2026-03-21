package no.synth.kmpzip.zip

internal actual class PlatformInflater actual constructor() {
    private var jvmInflater: java.util.zip.Inflater? = null

    actual fun init(nowrap: Boolean, gzip: Boolean) {
        jvmInflater = java.util.zip.Inflater(nowrap && !gzip)
    }

    actual fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult {
        val inf = jvmInflater ?: return InflateResult(0, 0, true)
        if (inf.finished()) return InflateResult(0, 0, true)

        val bytesReadBefore = inf.bytesRead

        if (inputLen > 0) {
            inf.setInput(input, inputOffset, inputLen)
        }

        val produced = inf.inflate(output, outputOffset, outputLen)
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
