package no.synth.kmpzip.zip

/**
 * The data of a ZIP64 extended information extra field (APPNOTE 4.5.3). It holds, in
 * this order, the uncompressed size, compressed size, local header offset (8 bytes
 * each) and disk start (4 bytes), but only those whose header field holds the marker.
 */
internal class Zip64Extra(private val extra: ByteArray, private val start: Int, val size: Int) {
    private var pos = 0

    /** Returns the next stored 8-byte value if [marked], otherwise [value] unchanged. */
    fun next(value: Long, marked: Boolean): Long {
        if (!marked) return value
        val v = longAt(pos)
        pos += 8
        return v
    }

    /** Reads the 8-byte value at [offset] into the field data. */
    fun longAt(offset: Int): Long {
        if (offset + 8 > size) throw ZipFormatException("ZIP64 extra field too short")
        val v = readLeLong(extra, start + offset)
        // A size or offset past Long.MAX_VALUE cannot exist in a real archive.
        if (v < 0) throw ZipFormatException("ZIP64 value out of range")
        return v
    }

    /** Returns the next stored 4-byte value if [marked], otherwise [value] unchanged. */
    fun nextInt(value: Long, marked: Boolean): Long {
        if (!marked) return value
        if (pos + 4 > size) throw ZipFormatException("ZIP64 extra field too short")
        val v = readLeUInt(extra, start + pos)
        pos += 4
        return v
    }

    companion object {
        /** Returns the ZIP64 field in [extra], or null if there is none. */
        fun find(extra: ByteArray?): Zip64Extra? {
            if (extra == null) return null
            var p = 0
            while (p + 4 <= extra.size) {
                val id = readLeShort(extra, p)
                val size = readLeShort(extra, p + 2)
                if (p + 4 + size > extra.size) return null
                if (id == ZipConstants.ZIP64_EXTRA_ID) return Zip64Extra(extra, p + 4, size)
                p += 4 + size
            }
            return null
        }
    }
}

/** Builds a ZIP64 extra field holding [values] as 8-byte little-endian numbers. */
internal fun zip64ExtraField(vararg values: Long): ByteArray {
    val out = ByteArray(4 + values.size * 8)
    writeLeShort(out, 0, ZipConstants.ZIP64_EXTRA_ID)
    writeLeShort(out, 2, values.size * 8)
    for (i in values.indices) writeLeLong(out, 4 + i * 8, values[i])
    return out
}

/**
 * Returns [extra] without any ZIP64 field, or null if nothing remains. The writer adds
 * its own ZIP64 field from the actual sizes, so one carried over from an entry read
 * from another archive must not reach the output.
 */
internal fun stripZip64Extra(extra: ByteArray?): ByteArray? {
    if (Zip64Extra.find(extra) == null) return extra
    val src = checkNotNull(extra)
    val out = ArrayList<Byte>(src.size)
    var p = 0
    while (p + 4 <= src.size) {
        val id = readLeShort(src, p)
        val end = minOf(p + 4 + readLeShort(src, p + 2), src.size)
        if (id != ZipConstants.ZIP64_EXTRA_ID) {
            for (i in p until end) out.add(src[i])
        }
        p = end
    }
    for (i in p until src.size) out.add(src[i])
    return if (out.isEmpty()) null else out.toByteArray()
}

private fun writeLeShort(out: ByteArray, offset: Int, v: Int) {
    out[offset] = (v and 0xFF).toByte()
    out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
}

private fun writeLeLong(out: ByteArray, offset: Int, v: Long) {
    for (i in 0 until 8) out[offset + i] = ((v ushr (8 * i)) and 0xFF).toByte()
}
