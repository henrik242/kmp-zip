package no.synth.kmpio.zip

object ZipConstants {
    const val STORED = 0
    const val DEFLATED = 8

    internal const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
    internal const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
    internal const val CENTRAL_DIR_HEADER_SIGNATURE = 0x02014b50
    internal const val END_OF_CENTRAL_DIR_SIGNATURE = 0x06054b50
}
