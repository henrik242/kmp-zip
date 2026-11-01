package no.synth.kmpzip.internal

// Kotlin/JS can alias a typed array over a ByteArray; Kotlin/Wasm must copy.
internal expect fun byteArrayToUint8Array(src: ByteArray, offset: Int, length: Int): Uint8Array

internal expect fun copyUint8ToByteArray(
    arr: Uint8Array, srcOffset: Int,
    output: ByteArray, dstOffset: Int, length: Int,
)
