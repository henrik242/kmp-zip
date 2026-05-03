package no.synth.kmpzip.kotlinx

import kotlinx.io.Source
import no.synth.kmpzip.io.InputStream

class SourceInputStream(private val source: Source) : InputStream() {

    override fun read(): Int {
        if (source.exhausted()) return -1
        return source.readByte().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        // kotlinx-io's readAtMostTo(ByteArray, …) does bulk segment copies
        // (memcpy under the hood), avoiding the per-byte readByte() loop that
        // dominated cost on multi-MB transfers.
        val n = source.readAtMostTo(b, off, off + len)
        return if (n <= 0) -1 else n
    }

    override fun available(): Int = 0

    override fun close() {
        source.close()
    }
}

fun Source.asInputStream(): InputStream = SourceInputStream(this)
