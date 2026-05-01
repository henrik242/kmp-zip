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

// Bulk Uint8Array → byte-identity String, then the K/Wasm bridge moves the
// String across the boundary in one copy. Cannot use `TextDecoder('latin1')`:
// the WHATWG Encoding spec aliases the `latin1` label to **windows-1252**,
// which remaps bytes 0x80..0x9F (e.g. 0x8B → U+2039) and breaks the
// byte-identity round-trip the gzip magic bytes depend on. `String.fromCharCode`
// has no such remapping. Chunked `apply` keeps allocation linear without
// blowing past JS engines' max-args-per-call limit on large arrays.
internal fun uint8ArrayToLatin1String(arr: Uint8Array, start: Int, length: Int): String =
    js("""(() => {
        const slice = arr.subarray(start, start + length);
        const CHUNK = 16384;
        let result = '';
        for (let i = 0; i < slice.length; i += CHUNK) {
            result += String.fromCharCode.apply(null, slice.subarray(i, i + CHUNK));
        }
        return result;
    })()""")

// Reverse direction: a Latin-1 String becomes a Uint8Array with the same
// numeric byte values. TextEncoder writes UTF-8, which we don't want; encoding
// via charCodeAt gives the byte-identity mapping for chars 0..255.
internal fun latin1StringToUint8Array(s: String, length: Int): Uint8Array =
    js("(() => { const arr = new Uint8Array(length); for (let i = 0; i < length; i++) arr[i] = s.charCodeAt(i); return arr; })()")

internal fun copyUint8ToByteArray(arr: Uint8Array, srcOffset: Int, output: ByteArray, dstOffset: Int, length: Int) {
    if (length == 0) return
    val str = uint8ArrayToLatin1String(arr, srcOffset, length)
    for (i in 0 until length) {
        output[dstOffset + i] = str[i].code.toByte()
    }
}

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

internal fun byteArrayToUint8Array(src: ByteArray, offset: Int, length: Int): Uint8Array {
    if (length == 0) return Uint8Array(0)
    // CharArray + concatToString is one Kotlin-side bulk copy and one bulk
    // String marshal across the boundary, vs N boundary calls for per-byte set.
    val chars = CharArray(length) { i -> (src[offset + i].toInt() and 0xff).toChar() }
    return latin1StringToUint8Array(chars.concatToString(), length)
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
