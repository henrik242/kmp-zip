package no.synth.kmpzip.deflate

import kotlin.jvm.JvmOverloads
import no.synth.kmpzip.io.NoProgressException
import no.synth.kmpzip.io.checkBounds
import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.zip.PlatformDeflater

/**
 * An output stream that compresses data as raw DEFLATE or zlib.
 *
 * Uses the platform codec: `java.util.zip.Deflater` on JVM, `platform.zlib` on native,
 * pako on js/wasmJs. [flush] passes on what has been written to the underlying stream
 * but does not force the compressor to emit pending data; [finish] or [close] does.
 *
 * @param output the underlying output stream
 * @param format [DeflateFormat.ZLIB] (default) or [DeflateFormat.RAW]
 * @param level compression level, 0-9, or -1 for the codec default
 */
class DeflateOutputStream @JvmOverloads constructor(
    private val output: OutputStream,
    format: DeflateFormat = DeflateFormat.ZLIB,
    level: Int = -1,
) : OutputStream() {
    private val deflater: PlatformDeflater
    private val buf = ByteArray(8192)
    private var closed = false
    private var finished = false

    init {
        require(level in -1..9) { "Invalid compression level: $level" }
        deflater = PlatformDeflater().also { it.init(level, nowrap = format == DeflateFormat.RAW) }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkBounds(b, off, len)
        if (closed) throw IllegalStateException("Stream closed")
        if (finished) throw IllegalStateException("Stream finished")
        if (len == 0) return

        var inputOffset = off
        var remaining = len
        while (remaining > 0) {
            val result = deflater.deflate(b, inputOffset, remaining, buf, 0, buf.size)
            inputOffset += result.bytesConsumed
            remaining -= result.bytesConsumed
            if (result.bytesProduced > 0) output.write(buf, 0, result.bytesProduced)
            // Neither side advanced, so another pass would do the same thing forever.
            if (result.bytesConsumed == 0 && result.bytesProduced == 0) {
                throw NoProgressException("Deflater made no progress")
            }
        }
    }

    /** Finishes the compressed data without closing the underlying stream. */
    fun finish() {
        if (closed) throw IllegalStateException("Stream closed")
        if (finished) return
        finished = true

        val emptyInput = ByteArray(0)
        while (true) {
            val result = deflater.deflate(emptyInput, 0, 0, buf, 0, buf.size, finish = true)
            if (result.bytesProduced > 0) output.write(buf, 0, result.bytesProduced)
            if (result.streamEnd) break
            // Finishing with no output and no stream end means the deflater is stuck.
            if (result.bytesProduced == 0) throw NoProgressException("Deflater made no progress finishing")
        }
    }

    override fun flush() {
        output.flush()
    }

    override fun close() {
        if (!closed) {
            try {
                if (!finished) finish()
            } finally {
                closed = true
                deflater.end()
                output.close()
            }
        }
    }
}
