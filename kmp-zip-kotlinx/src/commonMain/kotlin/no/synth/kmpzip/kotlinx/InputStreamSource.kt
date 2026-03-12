package no.synth.kmpzip.kotlinx

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import no.synth.kmpzip.io.InputStream

class InputStreamSource(private val inputStream: InputStream) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val bytes = ByteArray(minOf(byteCount, 8192).toInt())
        val n = inputStream.read(bytes, 0, bytes.size)
        if (n == -1) return -1
        sink.write(bytes, 0, n)
        return n.toLong()
    }

    override fun close() {
        inputStream.close()
    }
}

fun InputStream.asSource(): RawSource = InputStreamSource(this)
