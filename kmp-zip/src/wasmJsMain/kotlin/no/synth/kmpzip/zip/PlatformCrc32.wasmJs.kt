package no.synth.kmpzip.zip

// Pure-Kotlin CRC-32 (IEEE 802.3 polynomial, reflected). Used on wasmJs to avoid
// crossing the JS boundary and copying ByteArray <-> Uint8Array on every chunk
// just to reach pako's exported crc32(); for small/medium inputs the JS round-trip
// dominates the compute.
private val CRC32_TABLE = IntArray(256).also { table ->
    for (i in 0 until 256) {
        var c = i
        repeat(8) {
            c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        table[i] = c
    }
}

internal actual class PlatformCrc32 actual constructor() {
    private var crc: Int = 0.inv()

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        if (len == 0) return
        var c = crc
        for (i in offset until offset + len) {
            c = (c ushr 8) xor CRC32_TABLE[(c xor data[i].toInt()) and 0xff]
        }
        crc = c
    }

    actual fun getValue(): Long = (crc.inv().toLong()) and 0xFFFFFFFFL

    actual fun reset() {
        crc = 0.inv()
    }
}
