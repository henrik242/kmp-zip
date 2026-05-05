package no.synth.kmpzip.kotlinx

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal actual val defaultZipDispatcher: CoroutineContext = Dispatchers.Default
