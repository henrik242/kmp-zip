package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.zip.PlatformInflater

actual class GzipInputStream actual constructor(private val input: InputStream) : InputStream() {
    private val inflater = PlatformInflater().also { it.init(gzip = true) }
    private val inputBuf = ByteArray(8192)
    private var inputBufPos = 0
    private var inputBufLen = 0
    private var closed = false
    private var eof = false

    init {
        // Validate the gzip magic upfront so callers get a clear error instead
        // of a cryptic zlib `inflate failed: -3` mid-stream.
        var got = 0
        while (got < 2) {
            val n = input.read(inputBuf, got, 2 - got)
            if (n == -1) break
            got += n
        }
        if (got < 2 ||
            (inputBuf[0].toInt() and 0xFF) != 0x1F ||
            (inputBuf[1].toInt() and 0xFF) != 0x8B
        ) {
            throw Exception("Not in gzip format")
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
            // Try to inflate from the current input buffer
            if (inputBufLen > inputBufPos) {
                val result = inflater.inflate(
                    inputBuf, inputBufPos, inputBufLen - inputBufPos,
                    b, off, len
                )
                inputBufPos += result.bytesConsumed

                if (result.bytesProduced > 0) {
                    if (result.streamEnd) eof = true
                    return result.bytesProduced
                }
                if (result.streamEnd) {
                    eof = true
                    return -1
                }
            }

            // Need more input data
            val n = input.read(inputBuf, 0, inputBuf.size)
            if (n == -1) {
                eof = true
                return -1
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
