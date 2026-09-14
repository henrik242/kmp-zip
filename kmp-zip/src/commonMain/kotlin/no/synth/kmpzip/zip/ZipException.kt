package no.synth.kmpzip.zip

import kotlin.jvm.JvmOverloads
import no.synth.kmpzip.io.IOException

/**
 * Base type for exceptions raised by kmp-zip while reading ZIP data. Catch this to
 * handle any archive-level failure; catch a subtype to distinguish a malformed
 * archive from an unsupported feature or a wrong password.
 *
 * Extends [IOException] so JVM/Java consumers that wrap stream reads in
 * `catch (IOException)` keep working. Note the simple name collides with
 * `java.util.zip.ZipException` on the JVM - fully-qualify or alias if you import both.
 */
open class ZipException @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The archive is malformed, corrupt, or truncated: a bad or missing signature, a
 * truncated header, a failed integrity check (CRC or AES authentication), or bytes
 * that do not parse as ZIP.
 */
class ZipFormatException @JvmOverloads constructor(message: String, cause: Throwable? = null) : ZipException(message, cause)

/**
 * The archive is well-formed but uses a ZIP feature this library does not
 * implement, such as ZIP64, split/spanned archives, or an unsupported compression
 * method.
 */
class ZipUnsupportedFeatureException @JvmOverloads constructor(message: String, cause: Throwable? = null) : ZipException(message, cause)

/**
 * A password is required but missing, or the supplied password is wrong. For legacy
 * (PKWare ZipCrypto) entries there is no message authentication, so a failed check
 * cannot distinguish a wrong password from corrupt data; the message reflects that.
 */
class ZipPasswordException @JvmOverloads constructor(message: String, cause: Throwable? = null) : ZipException(message, cause)
