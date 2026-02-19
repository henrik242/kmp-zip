package no.synth.kmpio.zip

import no.synth.kmpio.io.InputStream

expect class ZipInputStream(input: InputStream) : InputStream {
    val nextEntry: ZipEntry?
    fun closeEntry()
    fun readBytes(): ByteArray
    override fun read(): Int
    override fun read(b: ByteArray, off: Int, len: Int): Int
    override fun available(): Int
    override fun close()
}

fun ZipInputStream(data: ByteArray): ZipInputStream {
    return ZipInputStream(no.synth.kmpio.io.ByteArrayInputStream(data))
}
