package no.synth.kmpzip.zip

import no.synth.kmpzlib.Crc32

// js/wasmJs have no platform zlib; kmp-zlib's slicing-by-4 CRC-32 is used instead.
internal actual class PlatformCrc32 actual constructor() {
    private val crc = Crc32()

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        crc.update(data, offset, len)
    }

    actual fun getValue(): Long = crc.value

    actual fun reset() {
        crc.reset()
    }
}
