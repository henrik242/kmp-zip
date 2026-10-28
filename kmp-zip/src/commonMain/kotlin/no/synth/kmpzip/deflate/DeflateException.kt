package no.synth.kmpzip.deflate

import kotlin.jvm.JvmOverloads
import no.synth.kmpzip.io.IOException

/**
 * Base type for exceptions raised by kmp-zip while reading raw DEFLATE or zlib data.
 * Catch this to handle any [InflateInputStream] failure.
 *
 * Extends [IOException] so JVM/Java consumers that wrap stream reads in
 * `catch (IOException)` keep working.
 */
open class DeflateException @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The stream is malformed, corrupt, or truncated: a bad zlib header or Adler-32
 * checksum, invalid DEFLATE data, a preset dictionary (not supported), or an
 * unexpected end before the compressed data finished.
 */
class DeflateFormatException @JvmOverloads constructor(message: String, cause: Throwable? = null) : DeflateException(message, cause)
