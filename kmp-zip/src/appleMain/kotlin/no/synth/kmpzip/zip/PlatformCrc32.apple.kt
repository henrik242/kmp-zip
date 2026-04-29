package no.synth.kmpzip.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.zlib.crc32

@OptIn(ExperimentalForeignApi::class)
internal actual fun zlibCrc32Update(crc: Long, data: ByteArray, offset: Int, len: Int): Long =
    data.usePinned { pinned ->
        crc32(crc.convert(), toUBytePointer(pinned, offset), len.toUInt()).toLong()
    }
