package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.OutputStream

/**
 * An output stream that compresses data in the GZIP format.
 *
 * On JVM, delegates to [java.util.zip.GZIPOutputStream].
 * On Native, uses `platform.zlib` with gzip wrapping.
 */
expect class GzipOutputStream(output: OutputStream) : OutputStream {
    override fun write(b: Int)
    override fun write(b: ByteArray, off: Int, len: Int)
    fun finish()
    override fun flush()
    override fun close()
}
