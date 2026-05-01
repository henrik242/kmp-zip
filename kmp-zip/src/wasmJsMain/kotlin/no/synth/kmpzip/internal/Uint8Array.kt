package no.synth.kmpzip.internal

// Minimal external binding for the JS-global Uint8Array. The K/Wasm 2.3 stdlib
// doesn't expose it, and pulling in kotlinx-browser just for one constructor
// would be overkill. Indexed access (arr[i]) isn't expressible as an external
// operator on K/Wasm, so reads/writes go through small js() helpers.
internal external class Uint8Array(size: Int) : JsAny {
    val length: Int
}
