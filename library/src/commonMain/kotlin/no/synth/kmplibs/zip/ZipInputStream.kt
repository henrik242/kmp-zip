package no.synth.kmplibs.zip

import no.synth.kmplibs.io.InputStream

expect class ZipInputStream(input: InputStream) : InputStream {
    fun getNextEntry(): ZipEntry?
    fun closeEntry()
    override fun read(): Int
    override fun read(b: ByteArray, off: Int, len: Int): Int
    override fun available(): Int
    override fun close()
}

fun ZipInputStream(data: ByteArray): ZipInputStream {
    return ZipInputStream(no.synth.kmplibs.io.ByteArrayInputStream(data))
}
