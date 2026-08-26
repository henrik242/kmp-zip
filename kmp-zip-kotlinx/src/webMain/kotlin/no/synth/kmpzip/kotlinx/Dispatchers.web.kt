package no.synth.kmpzip.kotlinx

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// js and wasmJs have no thread pool: Dispatchers.Default resolves to the JS
// event loop, so this neither offloads work nor preempts the caller. It's used
// for symmetry with the native actual, which carries the identical body; callers
// needing UI responsiveness should pass a custom dispatcher or break the work
// up themselves.
internal actual val defaultZipDispatcher: CoroutineContext = Dispatchers.Default
