package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.InputStream

actual class GzipInputStream actual constructor(input: InputStream) : InputStream() {
    private val jvmGis = java.util.zip.GZIPInputStream(input)

    actual override fun read(): Int = jvmGis.read()

    actual override fun read(b: ByteArray, off: Int, len: Int): Int = jvmGis.read(b, off, len)

    actual override fun available(): Int = jvmGis.available()

    actual override fun close() {
        jvmGis.close()
    }
}
