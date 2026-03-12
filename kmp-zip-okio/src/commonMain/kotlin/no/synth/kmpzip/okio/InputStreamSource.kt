package no.synth.kmpzip.okio

import okio.Buffer
import okio.Source
import okio.Timeout
import no.synth.kmpzip.io.InputStream

class InputStreamSource(private val inputStream: InputStream) : Source {

    override fun read(sink: Buffer, byteCount: Long): Long {
        val bytes = ByteArray(minOf(byteCount, 8192).toInt())
        val n = inputStream.read(bytes, 0, bytes.size)
        if (n == -1) return -1
        sink.write(bytes, 0, n)
        return n.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        inputStream.close()
    }
}

fun InputStream.asSource(): Source = InputStreamSource(this)
