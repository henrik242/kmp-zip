package no.synth.kmpzip.gzip

import no.synth.kmpzip.io.OutputStream

actual class GzipOutputStream actual constructor(output: OutputStream) : OutputStream() {
    private val jvmGos = java.util.zip.GZIPOutputStream(output)

    actual override fun write(b: Int) {
        jvmGos.write(b)
    }

    actual override fun write(b: ByteArray, off: Int, len: Int) {
        jvmGos.write(b, off, len)
    }

    actual fun finish() {
        jvmGos.finish()
    }

    actual override fun flush() {
        jvmGos.flush()
    }

    actual override fun close() {
        jvmGos.close()
    }
}
