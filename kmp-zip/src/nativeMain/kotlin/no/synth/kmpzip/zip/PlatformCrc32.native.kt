package no.synth.kmpzip.zip

// CRC-32 with the platform's zlib. The actual call is delegated to a per-target
// helper because zlib's `uLong` type maps to ULong on LP64 (Apple/Linux) but UInt
// on LLP64 (mingw), which the shared `nativeMain` metadata compiler can't unify
// (even with kotlinx.cinterop.convert() at the call site).
internal expect fun zlibCrc32Update(crc: Long, data: ByteArray, offset: Int, len: Int): Long

internal actual class PlatformCrc32 actual constructor() {
    private var crc: Long = 0L

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        if (len == 0) return
        crc = zlibCrc32Update(crc, data, offset, len)
    }

    actual fun getValue(): Long = crc and 0xFFFFFFFFL

    actual fun reset() {
        crc = 0L
    }
}
