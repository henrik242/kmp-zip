package no.synth.kmpzip.zip

internal actual class PlatformCrc32 actual constructor() {
    private val crc = java.util.zip.CRC32()

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        crc.update(data, offset, len)
    }

    actual fun getValue(): Long = crc.value

    actual fun reset() {
        crc.reset()
    }
}
