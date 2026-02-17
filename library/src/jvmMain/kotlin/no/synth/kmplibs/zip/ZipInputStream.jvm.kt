package no.synth.kmplibs.zip

import no.synth.kmplibs.io.InputStream

actual class ZipInputStream actual constructor(input: InputStream) : InputStream() {
    private val jvmZis = java.util.zip.ZipInputStream(input)

    actual val nextEntry: ZipEntry?
        get() {
            val entry = jvmZis.nextEntry ?: return null
            return ZipEntry(entry)
        }

    actual fun closeEntry() {
        jvmZis.closeEntry()
    }

    actual override fun read(): Int = jvmZis.read()

    actual override fun read(b: ByteArray, off: Int, len: Int): Int = jvmZis.read(b, off, len)

    actual override fun available(): Int = jvmZis.available()

    actual override fun close() {
        jvmZis.close()
    }
}
