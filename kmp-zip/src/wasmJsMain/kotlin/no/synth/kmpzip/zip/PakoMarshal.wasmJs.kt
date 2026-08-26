@file:Suppress("UNUSED_PARAMETER")

package no.synth.kmpzip.zip

import no.synth.kmpzip.internal.Uint8Array

// Kotlin/Wasm keeps ByteArray in linear memory, so there is no shared buffer to
// alias: every byte has to cross the boundary. A Latin-1 String is the cheapest
// bulk carrier the K/Wasm bridge offers, one marshal per direction instead of N
// boundary calls.

// Bulk Uint8Array → byte-identity String, then the K/Wasm bridge moves the
// String across the boundary in one copy. Cannot use `TextDecoder('latin1')`:
// the WHATWG Encoding spec aliases the `latin1` label to **windows-1252**,
// which remaps bytes 0x80..0x9F (e.g. 0x8B → U+2039) and breaks the
// byte-identity round-trip the gzip magic bytes depend on. `String.fromCharCode`
// has no such remapping. Chunked `apply` keeps allocation linear without
// blowing past JS engines' max-args-per-call limit on large arrays.
private fun uint8ArrayToLatin1String(arr: Uint8Array, start: Int, length: Int): String =
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
private fun latin1StringToUint8Array(s: String, length: Int): Uint8Array =
    js("(() => { const arr = new Uint8Array(length); for (let i = 0; i < length; i++) arr[i] = s.charCodeAt(i); return arr; })()")

internal actual fun copyUint8ToByteArray(
    arr: Uint8Array, srcOffset: Int,
    output: ByteArray, dstOffset: Int, length: Int,
) {
    if (length == 0) return
    val str = uint8ArrayToLatin1String(arr, srcOffset, length)
    for (i in 0 until length) {
        output[dstOffset + i] = str[i].code.toByte()
    }
}

internal actual fun byteArrayToUint8Array(src: ByteArray, offset: Int, length: Int): Uint8Array {
    if (length == 0) return Uint8Array(0)
    // CharArray + concatToString is one Kotlin-side bulk copy and one bulk
    // String marshal across the boundary, vs N boundary calls for per-byte set.
    val chars = CharArray(length) { i -> (src[offset + i].toInt() and 0xff).toChar() }
    return latin1StringToUint8Array(chars.concatToString(), length)
}
