package no.synth.kmpzip.deflate

import kotlin.jvm.JvmOverloads
import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.NoProgressException
import no.synth.kmpzip.io.checkBounds
import no.synth.kmpzip.zip.CodecException
import no.synth.kmpzip.zip.PlatformInflater

/**
 * An input stream that decompresses raw DEFLATE or zlib data.
 *
 * Uses the platform codec: `java.util.zip.Inflater` on JVM, `platform.zlib` on native,
 * pako on js/wasmJs. A zlib stream's Adler-32 checksum is verified when its end is read.
 *
 * The stream ends with the compressed data. Input is read ahead in chunks, so bytes
 * after the end of the compressed data may already have been consumed from [input].
 *
 * **Decompression-bomb safety.** This stream does not enforce a maximum
 * uncompressed size; a small input can expand to gigabytes. When decompressing
 * untrusted input, read in chunks and stop after a budget.
 *
 * @param input the compressed data
 * @param format [DeflateFormat.ZLIB] (default) or [DeflateFormat.RAW]
 */
class InflateInputStream @JvmOverloads constructor(
    private val input: InputStream,
    format: DeflateFormat = DeflateFormat.ZLIB,
) : InputStream() {
    private val inflater = PlatformInflater().also { it.init(nowrap = format == DeflateFormat.RAW) }
    private val inputBuf = ByteArray(8192)
    private var inputBufPos = 0
    private var inputBufLen = 0
    private var closed = false
    private var eof = false

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkBounds(b, off, len)
        if (len == 0) return 0
        if (closed || eof) return -1

        while (true) {
            // Always call inflate, even with no fresh input: the inflater may hold output
            // from an earlier push (the pako wrapper does), and needs calls to drain it.
            val result = try {
                inflater.inflate(inputBuf, inputBufPos, inputBufLen - inputBufPos, b, off, len)
            } catch (e: CodecException) {
                throw DeflateFormatException(e.message ?: "Corrupt deflate stream", e)
            }
            inputBufPos += result.bytesConsumed

            if (result.streamEnd) {
                eof = true
                return if (result.bytesProduced > 0) result.bytesProduced else -1
            }
            if (result.bytesProduced > 0) return result.bytesProduced

            // The inflater wants more input and should have consumed everything buffered.
            // The refill below starts at offset 0, so anything left behind would be lost.
            if (inputBufPos < inputBufLen) {
                throw NoProgressException("Inflater made no progress on the deflate stream")
            }

            val n = input.read(inputBuf, 0, inputBuf.size)
            if (n == -1) {
                throw DeflateFormatException("Truncated deflate stream: unexpected EOF before end of compressed data")
            }
            // Not EOF but no bytes either; without this the loop would spin.
            if (n == 0) {
                throw NoProgressException("Source returned no data before end of compressed data")
            }
            inputBufPos = 0
            inputBufLen = n
        }
    }

    override fun available(): Int = if (eof || closed) 0 else 1

    override fun close() {
        if (!closed) {
            closed = true
            inflater.end()
            input.close()
        }
    }
}

/** Convenience constructor for decompressing a byte array. */
@JvmOverloads
fun InflateInputStream(data: ByteArray, format: DeflateFormat = DeflateFormat.ZLIB): InflateInputStream =
    InflateInputStream(ByteArrayInputStream(data), format)
