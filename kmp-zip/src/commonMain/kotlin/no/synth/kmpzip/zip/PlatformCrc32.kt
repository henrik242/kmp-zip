package no.synth.kmpzip.zip

internal expect class PlatformCrc32() {
    fun update(data: ByteArray, offset: Int, len: Int)
    fun getValue(): Long
    fun reset()
}
