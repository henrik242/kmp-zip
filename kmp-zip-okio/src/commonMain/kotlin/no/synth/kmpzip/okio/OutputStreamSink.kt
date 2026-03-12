package no.synth.kmpzip.okio

import okio.Buffer
import okio.Sink
import okio.Timeout
import no.synth.kmpzip.io.OutputStream

class OutputStreamSink(private val outputStream: OutputStream) : Sink {

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val toRead = minOf(remaining, 8192).toInt()
            val bytes = source.readByteArray(toRead.toLong())
            outputStream.write(bytes, 0, toRead)
            remaining -= toRead
        }
    }

    override fun flush() {
        outputStream.flush()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        outputStream.close()
    }
}

fun OutputStream.asSink(): Sink = OutputStreamSink(this)
