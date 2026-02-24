package no.synth.kmpzip.okio

import okio.BufferedSink
import no.synth.kmpzip.io.OutputStream

class SinkOutputStream(private val sink: BufferedSink) : OutputStream() {

    override fun write(b: Int) {
        sink.writeByte(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
    }

    override fun flush() {
        sink.flush()
    }

    override fun close() {
        sink.close()
    }
}

fun BufferedSink.asOutputStream(): OutputStream = SinkOutputStream(this)
