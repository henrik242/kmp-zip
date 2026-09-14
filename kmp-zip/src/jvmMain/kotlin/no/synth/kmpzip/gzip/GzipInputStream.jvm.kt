package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.InputStream

actual class GzipInputStream actual constructor(input: InputStream) : InputStream() {
    // java.util.zip signals a bad header, corrupt trailer, or truncation with
    // java.util.zip.ZipException / EOFException. Map those to GzipFormatException so the
    // JVM target reports the same type as the pure-Kotlin targets. But the SAME types can
    // be thrown by the supplied source itself (its read() raising them). GZIPInputStream
    // lets a source exception propagate as the same instance, so record the last exception
    // the source threw and pass that one through unchanged instead of relabelling it.
    private var sourceFailure: Throwable? = null

    private val guardedInput = object : java.io.InputStream() {
        override fun read(): Int =
            try { input.read() } catch (e: Throwable) { sourceFailure = e; throw e }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            try { input.read(b, off, len) } catch (e: Throwable) { sourceFailure = e; throw e }

        override fun close() {
            input.close()
        }
    }

    private val jvmGis = try {
        java.util.zip.GZIPInputStream(guardedInput)
    } catch (e: java.util.zip.ZipException) {
        throw mapCodecFailure(e, "Not in gzip format")
    } catch (e: java.io.EOFException) {
        throw mapCodecFailure(e, "Truncated gzip stream")
    }

    actual override fun read(): Int = mapFormatErrors { jvmGis.read() }

    actual override fun read(b: ByteArray, off: Int, len: Int): Int = mapFormatErrors { jvmGis.read(b, off, len) }

    private inline fun <T> mapFormatErrors(block: () -> T): T = try {
        block()
    } catch (e: java.util.zip.ZipException) {
        throw mapCodecFailure(e, "Corrupt gzip stream")
    } catch (e: java.io.EOFException) {
        throw mapCodecFailure(e, "Truncated gzip stream")
    }

    // Relabel an exception GZIPInputStream raised about the gzip data as GzipFormatException,
    // but pass through unchanged the one the source itself threw (GZIPInputStream's own
    // truncation is a fresh EOFException, so it does not match `sourceFailure`).
    private fun mapCodecFailure(e: Throwable, fallback: String): Throwable =
        if (e === sourceFailure) e else GzipFormatException(e.message ?: fallback, e)

    actual override fun available(): Int = jvmGis.available()

    actual override fun close() {
        jvmGis.close()
    }
}
