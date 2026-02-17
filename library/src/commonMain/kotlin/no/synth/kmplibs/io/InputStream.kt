package no.synth.kmplibs.io

fun InputStream.readBytes(): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var totalSize = 0
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer, 0, buffer.size)
        if (n == -1) break
        chunks.add(buffer.copyOf(n))
        totalSize += n
    }
    val result = ByteArray(totalSize)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}

expect abstract class InputStream() : Closeable {
    abstract fun read(): Int
    open fun read(b: ByteArray): Int
    open fun read(b: ByteArray, off: Int, len: Int): Int
    open fun available(): Int
    open fun skip(n: Long): Long
    open fun mark(readlimit: Int)
    open fun reset()
    open fun markSupported(): Boolean
    override fun close()
}
