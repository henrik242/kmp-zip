package no.synth.kmpzip.io

/**
 * Multiplatform stand-in for `java.io.IOException`. On the JVM this IS
 * `java.io.IOException` (a typealias), so existing `catch (IOException)` handlers
 * around stream reads keep working; on every other target it is an equivalent
 * [Exception] subtype. The library's read-path exceptions extend this.
 */
expect open class IOException : Exception {
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}
