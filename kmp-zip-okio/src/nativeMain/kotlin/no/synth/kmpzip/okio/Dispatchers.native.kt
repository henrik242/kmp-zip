package no.synth.kmpzip.okio

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal actual val defaultZipDispatcher: CoroutineContext = Dispatchers.Default
