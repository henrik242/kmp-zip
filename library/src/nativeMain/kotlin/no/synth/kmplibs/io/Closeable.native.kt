package no.synth.kmplibs.io

actual interface Closeable : AutoCloseable {
    actual override fun close()
}
