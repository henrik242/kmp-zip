package no.synth.kmpio.zip

import no.synth.kmpio.io.OutputStream

actual class ZipOutputStream actual constructor(output: OutputStream) : OutputStream() {
    private val jvmZos = java.util.zip.ZipOutputStream(output)

    actual fun putNextEntry(entry: ZipEntry) {
        jvmZos.putNextEntry(entry.jvmEntry)
    }

    actual fun closeEntry() {
        jvmZos.closeEntry()
    }

    actual override fun write(b: Int) {
        jvmZos.write(b)
    }

    actual override fun write(b: ByteArray, off: Int, len: Int) {
        jvmZos.write(b, off, len)
    }

    actual fun finish() {
        jvmZos.finish()
    }

    actual override fun close() {
        jvmZos.close()
    }

    actual fun setComment(comment: String?) {
        jvmZos.setComment(comment)
    }

    actual fun setMethod(method: Int) {
        jvmZos.setMethod(method)
    }

    actual fun setLevel(level: Int) {
        jvmZos.setLevel(level)
    }

    override fun flush() {
        jvmZos.flush()
    }
}
