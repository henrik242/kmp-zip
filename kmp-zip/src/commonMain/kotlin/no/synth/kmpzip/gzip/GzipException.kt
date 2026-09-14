package no.synth.kmpzip.gzip

import kotlin.jvm.JvmOverloads
import no.synth.kmpzip.io.IOException

/**
 * Base type for exceptions raised by kmp-zip while reading GZIP data. Catch this to
 * handle any GZIP stream failure.
 *
 * Extends [IOException] so JVM/Java consumers that wrap stream reads in
 * `catch (IOException)` keep working.
 */
open class GzipException @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The stream is malformed, corrupt, or truncated: not in GZIP format, a bad
 * trailer, or an unexpected end before the compressed data finished.
 */
class GzipFormatException @JvmOverloads constructor(message: String, cause: Throwable? = null) : GzipException(message, cause)
