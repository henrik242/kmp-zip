package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.InputStream

/**
 * An input stream that decompresses data in the GZIP format.
 *
 * On JVM, delegates to [java.util.zip.GZIPInputStream].
 * On Native, uses `platform.zlib` with gzip wrapping.
 */
expect class GzipInputStream(input: InputStream) : InputStream {
    override fun read(): Int
    override fun read(b: ByteArray, off: Int, len: Int): Int
    override fun available(): Int
    override fun close()
}

fun GzipInputStream(data: ByteArray): GzipInputStream {
    return GzipInputStream(no.synth.kmpzip.io.ByteArrayInputStream(data))
}
