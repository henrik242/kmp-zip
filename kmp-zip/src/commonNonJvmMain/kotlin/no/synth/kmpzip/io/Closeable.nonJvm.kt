package no.synth.kmpzip.io

actual interface Closeable : AutoCloseable {
    actual override fun close()
}
