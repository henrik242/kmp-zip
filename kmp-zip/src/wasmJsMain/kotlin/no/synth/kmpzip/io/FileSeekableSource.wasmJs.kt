package no.synth.kmpzip.io

import no.synth.kmpzip.internal.Uint8Array
import no.synth.kmpzip.zip.copyUint8ToByteArray

// Node-only. In a browser there is no synchronous filesystem, so require('fs')
// throws — callers in a browser should use ByteArraySeekableSource instead.
actual fun fileSeekableSource(path: String): SeekableSource = NodeFileSeekableSource(path)

private fun fsOpen(path: String): Int = js("require('fs').openSync(path, 'r')")
private fun fsSize(fd: Int): Double = js("require('fs').fstatSync(fd).size")
private fun fsRead(fd: Int, buf: Uint8Array, length: Int, position: Double): Int =
    js("require('fs').readSync(fd, buf, 0, length, position)")
private fun fsClose(fd: Int) { js("require('fs').closeSync(fd)") }

private class NodeFileSeekableSource(path: String) : SeekableSource {
    private val fd = fsOpen(path)
    private var closed = false

    override val size: Long = fsSize(fd).toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IllegalStateException("Source closed")
        if (length == 0) return 0
        if (position >= size) return -1
        val buf = Uint8Array(length)
        val n = fsRead(fd, buf, length, position.toDouble())
        if (n <= 0) return -1
        copyUint8ToByteArray(buf, 0, into, offset, n)
        return n
    }

    override fun close() {
        if (!closed) {
            closed = true
            fsClose(fd)
        }
    }
}
