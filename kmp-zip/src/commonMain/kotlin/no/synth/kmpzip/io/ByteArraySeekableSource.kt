package no.synth.kmpzip.io

/**
 * A [SeekableSource] backed by an in-memory [ByteArray].
 *
 * Available on every target, including wasmJs in the browser. The whole archive is
 * held in memory; for large archives on memory-constrained platforms, prefer a
 * file-backed source where one is available.
 */
class ByteArraySeekableSource(private val data: ByteArray) : SeekableSource {
    private var closed = false

    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IllegalStateException("Source closed")
        if (position < 0) throw IllegalArgumentException("Negative position: $position")
        if (position >= data.size) return -1
        if (length == 0) return 0
        val available = data.size - position.toInt()
        val toRead = minOf(length, available)
        data.copyInto(into, offset, position.toInt(), position.toInt() + toRead)
        return toRead
    }

    override fun close() {
        closed = true
    }
}
