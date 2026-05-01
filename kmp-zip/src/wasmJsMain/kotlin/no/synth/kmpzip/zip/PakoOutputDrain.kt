package no.synth.kmpzip.zip

import no.synth.kmpzip.internal.Uint8Array

// Tracks how many bytes of pako's flattened `result` have been returned to
// the caller. While the stream is running, `result` is null and bytes come
// from the chunks array; once pako's onEnd() runs it flattens chunks into
// `result` and clears chunks, and we drain from `result` instead.
internal class PakoOutputDrain {
    private var resultRead: Int = 0

    fun draw(
        chunks: JsAny,
        result: Uint8Array?,
        output: ByteArray, offset: Int, len: Int,
    ): Int = if (result != null) {
        val pulled = drainResult(result, resultRead, output, offset, len)
        resultRead += pulled
        pulled
    } else {
        drainChunks(chunks, output, offset, len)
    }

    fun isStreamEnd(result: Uint8Array?): Boolean =
        result != null && resultRead == result.length

    fun reset() {
        resultRead = 0
    }
}
