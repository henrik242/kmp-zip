package no.synth.kmpio.io

actual open class ByteArrayOutputStream : OutputStream {
    private var buf: ByteArray
    private var count: Int = 0

    actual constructor() {
        buf = ByteArray(32)
    }

    actual constructor(size: Int) {
        require(size >= 0) { "Negative initial size: $size" }
        buf = ByteArray(size)
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buf.size) {
            val newCapacity = maxOf(buf.size * 2, minCapacity)
            buf = buf.copyOf(newCapacity)
        }
    }

    actual override fun write(b: Int) {
        ensureCapacity(count + 1)
        buf[count++] = b.toByte()
    }

    actual override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        ensureCapacity(count + len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }

    actual fun reset() {
        count = 0
    }

    actual fun size(): Int = count

    actual fun toByteArray(): ByteArray = buf.copyOf(count)

    actual fun writeTo(out: OutputStream) {
        out.write(buf, 0, count)
    }

    actual override fun close() {}

    override fun toString(): String = buf.decodeToString(0, count)
}
