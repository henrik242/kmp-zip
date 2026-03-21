package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.zip.PlatformDeflater

actual class GzipOutputStream actual constructor(private val output: OutputStream) : OutputStream() {
    private val deflater = PlatformDeflater().also { it.init(gzip = true) }
    private val buf = ByteArray(8192)
    private var closed = false
    private var finished = false

    actual override fun write(b: Int) {
        val single = byteArrayOf(b.toByte())
        write(single, 0, 1)
    }

    actual override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw Exception("Stream closed")
        if (len == 0) return

        var inputOffset = off
        var remaining = len
        var bytesProduced = buf.size // non-zero to enter the loop

        while (remaining > 0 || bytesProduced == buf.size) {
            val result = deflater.deflate(
                b, inputOffset, remaining,
                buf, 0, buf.size,
            )
            inputOffset += result.bytesConsumed
            remaining -= result.bytesConsumed
            bytesProduced = result.bytesProduced

            if (bytesProduced > 0) {
                output.write(buf, 0, bytesProduced)
            }
        }
    }

    actual fun finish() {
        if (finished) return
        finished = true

        val emptyInput = ByteArray(0)
        while (true) {
            val result = deflater.deflate(
                emptyInput, 0, 0,
                buf, 0, buf.size,
                finish = true,
            )
            if (result.bytesProduced > 0) {
                output.write(buf, 0, result.bytesProduced)
            }
            if (result.streamEnd) break
        }
    }

    actual override fun flush() {
        output.flush()
    }

    actual override fun close() {
        if (!closed) {
            closed = true
            if (!finished) finish()
            deflater.end()
            output.close()
        }
    }
}
