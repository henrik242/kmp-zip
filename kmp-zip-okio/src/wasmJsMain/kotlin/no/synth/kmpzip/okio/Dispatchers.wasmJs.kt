package no.synth.kmpzip.okio

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// wasmJs has no thread pool — Dispatchers.Default resolves to the JS event loop,
// so this neither offloads work nor preempts the caller. It's used for symmetry
// with the native actual; callers needing UI responsiveness should pass a
// custom dispatcher or break the work up themselves.
internal actual val defaultZipDispatcher: CoroutineContext = Dispatchers.Default
