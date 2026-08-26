@file:JsModule("node:fs")

package no.synth.kmpzip.io

import no.synth.kmpzip.internal.Uint8Array

// Node's synchronous fs, as a module import rather than a `require('fs')` call
// inside js(): a bare `require` is a free variable that doesn't exist in an ES
// module, so an ESM consumer would fail at runtime. `@JsModule` compiles to
// whichever form the consumer's module kind needs, and is dropped entirely when
// fileSeekableSource is unreachable.
internal external fun openSync(path: String, flags: String): Int
internal external fun fstatSync(fd: Int): Stats
internal external fun readSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int
internal external fun closeSync(fd: Int)

internal external class Stats : JsAny {
    val size: Double
}
