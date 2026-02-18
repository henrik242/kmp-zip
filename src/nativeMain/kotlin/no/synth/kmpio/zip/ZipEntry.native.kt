package no.synth.kmpio.zip

actual class ZipEntry {
    actual val name: String
    actual var size: Long
    actual var compressedSize: Long
    actual var crc: Long
    actual var method: Int
    actual var time: Long
    actual var comment: String?
    actual var extra: ByteArray?

    actual constructor(name: String) {
        this.name = name
        this.size = -1L
        this.compressedSize = -1L
        this.crc = -1L
        this.method = -1
        this.time = -1L
        this.comment = null
        this.extra = null
    }

    internal constructor(
        name: String,
        size: Long,
        compressedSize: Long,
        crc: Long,
        method: Int,
        time: Long,
        extra: ByteArray?,
    ) {
        this.name = name
        this.size = size
        this.compressedSize = compressedSize
        this.crc = crc
        this.method = method
        this.time = time
        this.comment = null
        this.extra = extra
    }

    actual val isDirectory: Boolean get() = name.endsWith('/')
}
