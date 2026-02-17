package no.synth.kmplibs.zip

expect class ZipEntry {
    val name: String
    var size: Long
    var compressedSize: Long
    var crc: Long
    var method: Int
    val isDirectory: Boolean
    var time: Long
    var comment: String?
    var extra: ByteArray?
}
