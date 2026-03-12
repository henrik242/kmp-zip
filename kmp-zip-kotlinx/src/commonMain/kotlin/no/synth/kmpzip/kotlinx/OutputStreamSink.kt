package no.synth.kmpzip.kotlinx

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import no.synth.kmpzip.io.OutputStream

class OutputStreamSink(private val outputStream: OutputStream) : RawSink {

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val toRead = minOf(remaining, 8192).toInt()
            val bytes = source.readByteArray(toRead)
            outputStream.write(bytes, 0, toRead)
            remaining -= toRead
        }
    }

    override fun flush() {
        outputStream.flush()
    }

    override fun close() {
        outputStream.close()
    }
}

fun OutputStream.asSink(): RawSink = OutputStreamSink(this)
