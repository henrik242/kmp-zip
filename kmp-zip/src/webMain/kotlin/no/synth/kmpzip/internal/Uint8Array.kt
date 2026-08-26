package no.synth.kmpzip.internal

// Minimal external binding for the JS-global Uint8Array. The K/Wasm stdlib has
// none, and K/JS's org.khronos.webgl one isn't reachable from a source set that
// also compiles for wasm; kotlinx-browser is overkill for one constructor.
// Indexed access (arr[i]) isn't expressible as an external operator on K/Wasm,
// so reads/writes go through small js() helpers.
internal external class Uint8Array(size: Int) : JsAny {
    val length: Int
}
