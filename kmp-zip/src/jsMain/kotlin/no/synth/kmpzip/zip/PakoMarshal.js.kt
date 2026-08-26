@file:Suppress("UNUSED_PARAMETER")

package no.synth.kmpzip.zip

import no.synth.kmpzip.internal.Uint8Array

// Kotlin/JS compiles ByteArray to a JS Int8Array, so a ByteArray and a
// Uint8Array can share one ArrayBuffer and both directions are a typed-array
// view or a bulk `set()` — no per-byte boundary traffic, unlike wasmJs.

// A view, not a copy. Safe because pako copies whatever it keeps into its own
// window during push() and never reads strm.input again after push() returns —
// Inflate does stop at Z_STREAM_END holding a live reference, but the next push
// reassigns it before reading. PakoMarshalTest keeps a canary on that.
// Kotlin never hands out a ByteArray backed by a view, so `byteOffset` is always
// 0; it is there for a Uint8Array unsafeCast in from consumer code.
private fun uint8ArrayView(src: ByteArray, offset: Int, length: Int): Uint8Array =
    js("new Uint8Array(src.buffer, src.byteOffset + offset, length)")

// Int8Array.set() applies ToInt8 per element, so 0..255 maps back to the same
// bit pattern as a signed Kotlin Byte.
private fun setInto(output: ByteArray, dstOffset: Int, arr: Uint8Array, srcOffset: Int, length: Int) {
    js("output.set(arr.subarray(srcOffset, srcOffset + length), dstOffset)")
}

internal actual fun copyUint8ToByteArray(
    arr: Uint8Array, srcOffset: Int,
    output: ByteArray, dstOffset: Int, length: Int,
) {
    if (length == 0) return
    setInto(output, dstOffset, arr, srcOffset, length)
}

internal actual fun byteArrayToUint8Array(src: ByteArray, offset: Int, length: Int): Uint8Array {
    if (length == 0) return Uint8Array(0)
    return uint8ArrayView(src, offset, length)
}
