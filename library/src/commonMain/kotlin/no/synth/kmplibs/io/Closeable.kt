package no.synth.kmplibs.io

expect interface Closeable : AutoCloseable {
    override fun close()
}
