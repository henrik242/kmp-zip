package no.synth.kmpzip.zip

import kotlinx.cinterop.*
import platform.zlib.crc32

@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformCrc32 actual constructor() {
    private var crc: ULong = crc32(0u, null, 0u)

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        if (len == 0) return
        data.usePinned { pinned ->
            crc = crc32(crc, toUBytePointer(pinned, offset), len.toUInt())
        }
    }

    actual fun getValue(): Long = crc.toLong() and 0xFFFFFFFFL

    actual fun reset() {
        crc = crc32(0u, null, 0u)
    }
}
