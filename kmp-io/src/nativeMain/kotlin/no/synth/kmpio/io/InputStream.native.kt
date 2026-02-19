package no.synth.kmpio.io

actual abstract class InputStream actual constructor() : Closeable {
    actual abstract fun read(): Int

    actual open fun read(b: ByteArray): Int = read(b, 0, b.size)

    actual open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var c = read()
        if (c == -1) return -1
        b[off] = c.toByte()
        var i = 1
        while (i < len) {
            c = read()
            if (c == -1) break
            b[off + i] = c.toByte()
            i++
        }
        return i
    }

    actual open fun available(): Int = 0

    actual open fun skip(n: Long): Long {
        var remaining = n
        val buf = ByteArray(minOf(2048, remaining.toInt()))
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val skipped = read(buf, 0, toRead)
            if (skipped <= 0) break
            remaining -= skipped
        }
        return n - remaining
    }

    actual open fun mark(readlimit: Int) {}

    actual open fun reset() {
        throw Exception("mark/reset not supported")
    }

    actual open fun markSupported(): Boolean = false

    actual override fun close() {}
}
