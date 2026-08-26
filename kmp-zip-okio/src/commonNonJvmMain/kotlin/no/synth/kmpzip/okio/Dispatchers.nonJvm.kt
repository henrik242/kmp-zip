package no.synth.kmpzip.okio

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// Dispatchers.Default is a real thread pool on native, but on js and wasmJs it
// resolves to the JS event loop, where this neither offloads work nor preempts
// the caller. Kept uniform for symmetry with jvm's Dispatchers.IO; callers
// needing UI responsiveness should pass a custom dispatcher or break the work
// up themselves.
internal actual val defaultZipDispatcher: CoroutineContext = Dispatchers.Default
