package no.synth.kmpzip.zip

import no.synth.kmpzlib.Inflater
import no.synth.kmpzlib.ZlibDataException
import no.synth.kmpzlib.ZlibFormat

internal actual class PlatformInflater actual constructor() {
    private var inflater: Inflater? = null

    actual fun init(nowrap: Boolean, gzip: Boolean) {
        val format = when {
            gzip -> ZlibFormat.GZIP
            nowrap -> ZlibFormat.RAW
            else -> ZlibFormat.ZLIB
        }
        inflater = Inflater(format)
    }

    actual fun reset() {
        val inf = inflater ?: throw IllegalStateException("Inflater not initialized")
        inf.reset()
    }

    actual fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult {
        val inf = inflater ?: throw IllegalStateException("Inflater not initialized")
        val r = try {
            inf.inflate(input, inputOffset, inputLen, output, outputOffset, outputLen)
        } catch (e: ZlibDataException) {
            throw CodecException("inflate failed: ${e.message}", e)
        }
        return InflateResult(r.bytesConsumed, r.bytesProduced, r.streamEnd)
    }

    actual fun end() {
        inflater = null
    }

    actual val isFinished: Boolean get() = inflater?.isFinished ?: true
}
