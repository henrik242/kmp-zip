package no.synth.kmpzip.zip

/** Reads a 16-bit unsigned little-endian value from [data] at [offset]. */
internal fun readLeShort(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8)

/** Reads a 32-bit unsigned little-endian value from [data] at [offset], as a Long. */
internal fun readLeUInt(data: ByteArray, offset: Int): Long =
    ((data[offset].toInt() and 0xFF).toLong()) or
        (((data[offset + 1].toInt() and 0xFF).toLong()) shl 8) or
        (((data[offset + 2].toInt() and 0xFF).toLong()) shl 16) or
        (((data[offset + 3].toInt() and 0xFF).toLong()) shl 24)
