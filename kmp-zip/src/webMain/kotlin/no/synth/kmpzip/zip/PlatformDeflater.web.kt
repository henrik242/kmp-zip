package no.synth.kmpzip.zip

import no.synth.kmpzlib.Deflater
import no.synth.kmpzlib.ZlibFormat

internal actual class PlatformDeflater actual constructor() {
    private var deflater: Deflater? = null

    actual fun init(level: Int, nowrap: Boolean, gzip: Boolean) {
        // gzip wins over nowrap, as on the other targets.
        val format = when {
            gzip -> ZlibFormat.GZIP
            nowrap -> ZlibFormat.RAW
            else -> ZlibFormat.ZLIB
        }
        deflater = Deflater(level, format)
    }

    actual fun deflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
        finish: Boolean,
    ): DeflateResult {
        val d = deflater ?: throw IllegalStateException("Deflater not initialized")
        val r = d.deflate(
            input, inputOffset, inputLen,
            output, outputOffset, outputLen,
            finish,
        )
        return DeflateResult(r.bytesConsumed, r.bytesProduced, r.streamEnd)
    }

    actual fun end() {
        deflater = null
    }

    actual val isFinished: Boolean get() = deflater?.isFinished ?: true
}
