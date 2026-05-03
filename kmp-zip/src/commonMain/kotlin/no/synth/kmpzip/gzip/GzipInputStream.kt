package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.InputStream

/**
 * An input stream that decompresses data in the GZIP format.
 *
 * On JVM, delegates to [java.util.zip.GZIPInputStream].
 * On Native, uses `platform.zlib` with gzip wrapping.
 *
 * **Decompression-bomb safety.** This stream does not enforce a maximum
 * uncompressed size. A small gzip input can expand to gigabytes (a "zip bomb").
 * When decompressing untrusted input, the caller must bound the work — read in
 * chunks and stop after a budget, or wrap with a size-limiting input stream.
 * Do not pass the result of [no.synth.kmpzip.io.readBytes] on this stream
 * directly to user-controlled archives.
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
