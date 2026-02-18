package no.synth.kmpio.io

actual interface Closeable : AutoCloseable {
    actual override fun close()
}
