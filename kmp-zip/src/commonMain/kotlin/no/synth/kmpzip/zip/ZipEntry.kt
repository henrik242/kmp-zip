package no.synth.kmpzip.zip

class ZipEntry(val name: String) {
    var size: Long = -1L
    var compressedSize: Long = -1L
    var crc: Long = -1L
    var method: Int = -1
    val isDirectory: Boolean get() = name.endsWith('/')
    var time: Long = -1L
    var comment: String? = null
    var extra: ByteArray? = null

    internal constructor(
        name: String,
        size: Long,
        compressedSize: Long,
        crc: Long,
        method: Int,
        time: Long,
        extra: ByteArray?,
    ) : this(name) {
        this.size = size
        this.compressedSize = compressedSize
        this.crc = crc
        this.method = method
        this.time = time
        this.extra = extra
    }
}
