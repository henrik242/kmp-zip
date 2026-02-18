package no.synth.kmpio.io

actual open class ByteArrayInputStream : InputStream {
    private val buf: ByteArray
    private var pos: Int
    private var mark: Int
    private var count: Int

    actual constructor(buf: ByteArray) {
        this.buf = buf
        this.pos = 0
        this.mark = 0
        this.count = buf.size
    }

    actual constructor(buf: ByteArray, offset: Int, length: Int) {
        this.buf = buf
        this.pos = offset
        this.mark = offset
        this.count = minOf(offset + length, buf.size)
    }

    actual override fun read(): Int {
        return if (pos < count) buf[pos++].toInt() and 0xFF else -1
    }

    actual override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= count) return -1
        val toRead = minOf(len, count - pos)
        if (toRead <= 0) return 0
        buf.copyInto(b, off, pos, pos + toRead)
        pos += toRead
        return toRead
    }

    actual override fun available(): Int = count - pos

    actual override fun skip(n: Long): Long {
        val toSkip = minOf(n, (count - pos).toLong())
        pos += toSkip.toInt()
        return toSkip
    }

    actual override fun mark(readlimit: Int) {
        mark = pos
    }

    actual override fun reset() {
        pos = mark
    }

    actual override fun markSupported(): Boolean = true

    actual override fun close() {}
}
