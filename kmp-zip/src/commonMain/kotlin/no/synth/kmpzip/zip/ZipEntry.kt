package no.synth.kmpzip.zip

class ZipEntry {
    val name: String
    var size: Long
    var compressedSize: Long
    var crc: Long
    var method: Int
    val isDirectory: Boolean get() = name.endsWith('/')
    var time: Long
    var comment: String?
    var extra: ByteArray?

    constructor(name: String) {
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
}
