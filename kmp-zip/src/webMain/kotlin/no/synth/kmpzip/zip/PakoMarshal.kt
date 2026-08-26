@file:Suppress("UNUSED_PARAMETER")

package no.synth.kmpzip.zip

import no.synth.kmpzip.internal.Uint8Array

internal const val Z_NO_FLUSH = 0
internal const val Z_FINISH = 4
internal const val Z_BUF_ERROR = -5

internal fun chunksLength(chunks: JsAny): Int =
    js("chunks.length")

internal fun chunksHead(chunks: JsAny): Uint8Array =
    js("chunks[0]")

internal fun chunksShift(chunks: JsAny): Unit =
    js("chunks.shift()")

// Replace the head chunk with its tail starting at `prefix` — used when the
// caller's output fills mid-chunk and we need to remember where to resume.
internal fun chunksTrimHead(chunks: JsAny, prefix: Int): Unit =
    js("chunks[0] = chunks[0].subarray(prefix)")

internal fun strmAvailIn(strm: JsAny): Int =
    js("strm.avail_in")

// Kotlin/JS can alias a typed array over a ByteArray; Kotlin/Wasm must copy.
internal expect fun byteArrayToUint8Array(src: ByteArray, offset: Int, length: Int): Uint8Array

internal expect fun copyUint8ToByteArray(
    arr: Uint8Array, srcOffset: Int,
    output: ByteArray, dstOffset: Int, length: Int,
)

internal fun drainChunks(chunks: JsAny, output: ByteArray, offset: Int, len: Int): Int {
    if (len == 0) return 0
    var produced = 0
    while (produced < len && chunksLength(chunks) > 0) {
        val head = chunksHead(chunks)
        val headLen = head.length
        val room = len - produced
        if (headLen <= room) {
            copyUint8ToByteArray(head, 0, output, offset + produced, headLen)
            produced += headLen
            chunksShift(chunks)
        } else {
            copyUint8ToByteArray(head, 0, output, offset + produced, room)
            produced += room
            chunksTrimHead(chunks, room)
        }
    }
    return produced
}

internal fun drainResult(
    result: Uint8Array, readFrom: Int,
    output: ByteArray, offset: Int, len: Int,
): Int {
    val total = result.length
    val available = total - readFrom
    if (available <= 0 || len == 0) return 0
    val toCopy = minOf(available, len)
    copyUint8ToByteArray(result, readFrom, output, offset, toCopy)
    return toCopy
}

// pako's option normalization treats `raw`/`gzip` flags as canonical and only
// synthesizes windowBits when they're absent — pass the flags rather than
// encoding via windowBits so the deflate side picks up the gzip wrapper.
internal fun pakoDeflateOptions(level: Int, raw: Boolean, gzip: Boolean, chunkSize: Int): JsAny =
    js("({ level: level, raw: raw, gzip: gzip, chunkSize: chunkSize })")

// Inflate auto-detects format when windowBits is left at the implicit default
// (>= 32), which is too forgiving for our use — set it explicitly.
internal fun pakoInflateOptions(raw: Boolean, gzip: Boolean, chunkSize: Int): JsAny =
    js("({ raw: raw, windowBits: gzip ? 31 : 15, chunkSize: chunkSize })")
