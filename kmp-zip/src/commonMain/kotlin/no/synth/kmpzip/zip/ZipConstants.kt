package no.synth.kmpzip.zip

object ZipConstants {
    const val STORED = 0
    const val DEFLATED = 8
    /** Compression method marker for WinZip AES encrypted entries. */
    const val AE_ENCRYPTED = 99

    internal const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
    internal const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
    internal const val CENTRAL_DIR_HEADER_SIGNATURE = 0x02014b50
    internal const val END_OF_CENTRAL_DIR_SIGNATURE = 0x06054b50

    /** Minimum version needed to extract AES encrypted entries. */
    internal const val VERSION_AES = 51
    internal const val VERSION_DEFAULT = 20
}
