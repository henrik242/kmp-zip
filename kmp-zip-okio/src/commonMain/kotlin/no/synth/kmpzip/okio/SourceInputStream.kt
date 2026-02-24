package no.synth.kmpzip.okio

import okio.BufferedSource
import no.synth.kmpzip.io.InputStream

class SourceInputStream(private val source: BufferedSource) : InputStream() {

    override fun read(): Int {
        if (source.exhausted()) return -1
        return source.readByte().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (source.exhausted()) return -1
        return source.read(b, off, len)
    }

    override fun available(): Int = 0

    override fun close() {
        source.close()
    }
}

fun BufferedSource.asInputStream(): InputStream = SourceInputStream(this)
