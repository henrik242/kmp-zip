package no.synth.kmpio.zip

actual class ZipEntry internal constructor(internal val jvmEntry: java.util.zip.ZipEntry) {
    actual constructor(name: String) : this(java.util.zip.ZipEntry(name))
    actual val name: String get() = jvmEntry.name
    actual var size: Long
        get() = jvmEntry.size
        set(value) { jvmEntry.size = value }
    actual var compressedSize: Long
        get() = jvmEntry.compressedSize
        set(value) { jvmEntry.compressedSize = value }
    actual var crc: Long
        get() = jvmEntry.crc
        set(value) { jvmEntry.crc = value }
    actual var method: Int
        get() = jvmEntry.method
        set(value) { jvmEntry.method = value }
    actual val isDirectory: Boolean get() = jvmEntry.isDirectory
    actual var time: Long
        get() = jvmEntry.time
        set(value) { jvmEntry.time = value }
    actual var comment: String?
        get() = jvmEntry.comment
        set(value) { jvmEntry.comment = value }
    actual var extra: ByteArray?
        get() = jvmEntry.extra
        set(value) { jvmEntry.extra = value }
}
