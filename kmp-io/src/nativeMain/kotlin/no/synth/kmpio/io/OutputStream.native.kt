package no.synth.kmpio.io

actual abstract class OutputStream actual constructor() : Closeable {
    actual abstract fun write(b: Int)

    actual open fun write(b: ByteArray) = write(b, 0, b.size)

    actual open fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            write(b[i].toInt())
        }
    }

    actual open fun flush() {}

    actual override fun close() {}
}
