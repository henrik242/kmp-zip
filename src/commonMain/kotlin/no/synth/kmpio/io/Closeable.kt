package no.synth.kmpio.io

expect interface Closeable : AutoCloseable {
    override fun close()
}
