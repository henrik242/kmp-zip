package no.synth.kmpzip.io

import no.synth.kmpzip.internal.Uint8Array
import no.synth.kmpzip.zip.copyUint8ToByteArray

// Node-only. A browser has no synchronous filesystem and no `node:fs` module, so
// callers in a browser should use ByteArraySeekableSource instead. Merely
// referencing this function from a browser bundle fails the webpack build with
// "Can't resolve 'node:fs'"; keep it behind a Node-only entry point.
actual fun fileSeekableSource(path: String): SeekableSource = NodeFileSeekableSource(path)

private class NodeFileSeekableSource(path: String) : SeekableSource {
    private val fd = openSync(path, "r")
    private var closed = false

    override val size: Long = fstatSync(fd).size.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IllegalStateException("Source closed")
        if (length == 0) return 0
        if (position >= size) return -1
        val buf = Uint8Array(length)
        val n = readSync(fd, buf, 0, length, position.toDouble())
        if (n <= 0) return -1
        copyUint8ToByteArray(buf, 0, into, offset, n)
        return n
    }

    override fun close() {
        if (!closed) {
            closed = true
            closeSync(fd)
        }
    }
}
