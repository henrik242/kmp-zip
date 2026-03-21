package no.synth.kmpzip.zip

internal data class DeflateResult(
    val bytesConsumed: Int,
    val bytesProduced: Int,
    val streamEnd: Boolean,
)

internal expect class PlatformDeflater() {
    /**
     * Initialize the deflater.
     * @param level compression level (-1 for default, 0-9)
     * @param nowrap true for raw deflate (ZIP), false for zlib wrapping
     * @param gzip true for gzip wrapping (overrides nowrap)
     */
    fun init(level: Int = -1, nowrap: Boolean = true, gzip: Boolean = false)
    fun deflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
        finish: Boolean = false,
    ): DeflateResult
    fun end()
    val isFinished: Boolean
}
