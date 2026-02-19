package no.synth.kmpio.kotlinx

import kotlinx.io.Buffer
import kotlinx.io.Source
import no.synth.kmpio.io.InputStream

class SourceInputStream(private val source: Source) : InputStream() {

    override fun read(): Int {
        if (source.exhausted()) return -1
        return source.readByte().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (source.exhausted()) return -1
        val buffer = Buffer()
        val bytesRead = source.readAtMostTo(buffer, len.toLong())
        if (bytesRead == 0L) return -1
        val count = bytesRead.toInt()
        for (i in 0 until count) {
            b[off + i] = buffer.readByte()
        }
        return count
    }

    override fun available(): Int = 0

    override fun close() {
        source.close()
    }
}

fun Source.asInputStream(): InputStream = SourceInputStream(this)
