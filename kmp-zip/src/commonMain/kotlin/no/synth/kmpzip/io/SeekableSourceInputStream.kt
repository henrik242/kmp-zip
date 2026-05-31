package no.synth.kmpzip.io

/**
 * A forward-only [InputStream] view over a [SeekableSource], starting at [start]
 * and running to the end of the source.
 *
 * Each instance keeps its own cursor, so several views over the same source can be
 * read independently. Closing the view does **not** close the underlying source —
 * the source is owned by whoever created it (e.g. [no.synth.kmpzip.zip.ZipFile]).
 */
internal class SeekableSourceInputStream(
    private val source: SeekableSource,
    start: Long,
) : InputStream() {
    private var position = start

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = source.read(position, b, off, len)
        if (n > 0) position += n
        return n
    }

    override fun available(): Int {
        val remaining = source.size - position
        return if (remaining <= 0) 0 else if (remaining > Int.MAX_VALUE) Int.MAX_VALUE else remaining.toInt()
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val remaining = source.size - position
        val skipped = minOf(n, maxOf(0, remaining))
        position += skipped
        return skipped
    }

    override fun close() {
        // Intentionally does not close the shared source.
    }
}
