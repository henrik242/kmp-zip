package no.synth.kmplibs.io

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
