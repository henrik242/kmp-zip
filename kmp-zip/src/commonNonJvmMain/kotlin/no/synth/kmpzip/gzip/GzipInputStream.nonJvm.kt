package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.NoProgressException
import no.synth.kmpzip.zip.CodecException
import no.synth.kmpzip.zip.PlatformInflater

actual class GzipInputStream actual constructor(private val input: InputStream) : InputStream() {
    private val inflater = PlatformInflater().also { it.init(gzip = true) }
    private val inputBuf = ByteArray(8192)
    private var inputBufPos = 0
    private var inputBufLen = 0
    private var closed = false
    private var eof = false

    init {
        // The inflater is already allocated by the property initialiser above, and a throw
        // from here never reaches close(), so release it before propagating.
        try {
            validateHeader()
        } catch (e: Throwable) {
            inflater.end()
            throw e
        }
    }

    private fun validateHeader() {
        // Validate the gzip magic upfront so callers get a clear error instead
        // of a cryptic zlib `inflate failed: -3` mid-stream.
        var got = 0
        while (got < 2) {
            val n = input.read(inputBuf, got, 2 - got)
            if (n == -1) break
            if (n == 0) {
                throw NoProgressException("Source returned no data while reading the gzip header")
            }
            got += n
        }
        if (got < 2 ||
            (inputBuf[0].toInt() and 0xFF) != 0x1F ||
            (inputBuf[1].toInt() and 0xFF) != 0x8B
        ) {
            throw GzipFormatException("Not in gzip format")
        }
        inputBufLen = got
    }

    actual override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    actual override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed || eof) return -1
        if (len == 0) return 0

        while (true) {
            // Always call inflate, even with no fresh input. The inflater may
            // hold buffered output left over from a previous push (the wasmJs
            // pako wrapper accumulates output internally and needs subsequent
            // calls to drain it). On native/zlib this is a cheap no-op.
            val available = inputBufLen - inputBufPos
            // A bad deflate stream or a failed gzip CRC/ISIZE trailer check surfaces from the
            // codec as a CodecException; type it as GzipFormatException. Programmer errors
            // (bad off/len, uninitialized inflater) are left to propagate, as on the JVM.
            val result = try {
                inflater.inflate(inputBuf, inputBufPos, available, b, off, len)
            } catch (e: CodecException) {
                throw GzipFormatException(e.message ?: "Corrupt gzip stream", e)
            }
            inputBufPos += result.bytesConsumed

            if (result.streamEnd) {
                // Member finished. Check whether more data follows — RFC 1952 §2.2
                // permits concatenated members and standard tools (gunzip, java.util.zip)
                // decode them all.
                if (inputBufPos == inputBufLen) {
                    // Probing for a following member: no more data means no more
                    // members. This cannot spin, so a 0 is treated as end of input
                    // rather than failing a member that already decoded cleanly.
                    val n = input.read(inputBuf, 0, inputBuf.size)
                    if (n <= 0) {
                        eof = true
                        return if (result.bytesProduced > 0) result.bytesProduced else -1
                    }
                    inputBufPos = 0
                    inputBufLen = n
                }
                inflater.reset()
                if (result.bytesProduced > 0) return result.bytesProduced
                continue
            }

            if (result.bytesProduced > 0) {
                return result.bytesProduced
            }

            // The inflater wants more input and should have consumed everything buffered.
            // The refill below always starts at offset 0, so anything left behind would be
            // silently dropped and resurface later as a truncation or CRC error.
            if (inputBufPos < inputBufLen) {
                throw NoProgressException("Inflater made no progress on the gzip stream")
            }

            // Produced nothing and not at stream end → need more input.
            val n = input.read(inputBuf, 0, inputBuf.size)
            if (n == -1) {
                throw GzipFormatException("Truncated gzip stream: unexpected EOF before end of compressed data")
            }
            // Not EOF but no bytes either; without this the loop would spin.
            if (n == 0) {
                throw NoProgressException("Source returned no data before end of compressed data")
            }
            inputBufPos = 0
            inputBufLen = n
        }
    }

    actual override fun available(): Int {
        return if (eof || closed) 0 else 1
    }

    actual override fun close() {
        if (!closed) {
            closed = true
            inflater.end()
            input.close()
        }
    }
}
