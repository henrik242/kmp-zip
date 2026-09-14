package no.synth.kmpzip.io

import kotlin.jvm.JvmOverloads

/**
 * Thrown when a stream or codec stops making progress: a source that reports neither
 * data nor end of input, or a deflater/inflater that neither consumes input nor
 * produces output. Either would otherwise loop forever, burning CPU instead of failing.
 *
 * This means a misbehaving source rather than a corrupt archive. An [InputStream] must
 * return `-1` at end of input, and [SeekableSource] states the same rule for a
 * non-empty request; returning `0` instead violates both.
 *
 * Extends [IOException] so it shares a catchable root with the other read-path failures.
 */
class NoProgressException @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)
