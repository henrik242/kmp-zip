package no.synth.kmplibs.zip

actual class ZipEntry(
    actual val name: String,
    actual var size: Long = -1L,
    actual var compressedSize: Long = -1L,
    actual var crc: Long = -1L,
    actual var method: Int = -1,
    actual var time: Long = -1L,
    actual var comment: String? = null,
    actual var extra: ByteArray? = null,
) {
    actual val isDirectory: Boolean get() = name.endsWith('/')
}
