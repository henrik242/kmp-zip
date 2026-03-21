package no.synth.kmpzip.zip

internal data class InflateResult(
    val bytesConsumed: Int,
    val bytesProduced: Int,
    val streamEnd: Boolean,
)

internal expect class PlatformInflater() {
    /**
     * Initialize the inflater.
     * @param nowrap true for raw inflate (ZIP), false for zlib wrapping
     * @param gzip true for gzip wrapping (overrides nowrap)
     */
    fun init(nowrap: Boolean = true, gzip: Boolean = false)
    fun inflate(
        input: ByteArray, inputOffset: Int, inputLen: Int,
        output: ByteArray, outputOffset: Int, outputLen: Int,
    ): InflateResult
    fun end()
    val isFinished: Boolean
}
