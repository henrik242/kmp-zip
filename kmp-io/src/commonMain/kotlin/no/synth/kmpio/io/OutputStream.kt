package no.synth.kmpio.io

expect abstract class OutputStream() : Closeable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray)
    open fun write(b: ByteArray, off: Int, len: Int)
    open fun flush()
    override fun close()
}
