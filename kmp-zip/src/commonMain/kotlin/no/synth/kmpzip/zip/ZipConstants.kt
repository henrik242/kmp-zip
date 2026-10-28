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
    internal const val ZIP64_END_OF_CENTRAL_DIR_SIGNATURE = 0x06064b50
    internal const val ZIP64_END_OF_CENTRAL_DIR_LOCATOR_SIGNATURE = 0x07064b50

    /** Header ID of the ZIP64 extended information extra field. */
    internal const val ZIP64_EXTRA_ID = 0x0001

    /** A 32-bit size or offset field holding this value is stored in the ZIP64 extra field. */
    internal const val ZIP64_MAGIC = 0xFFFFFFFFL

    /** A 16-bit count or disk field holding this value is stored in a ZIP64 record. */
    internal const val ZIP64_MAGIC_COUNT = 0xFFFF

    /** DOS date/time for 1980-01-01 00:00, the earliest a ZIP timestamp can express. */
    internal const val DOS_EPOCH = 0x00210000L

    /** Minimum version needed to extract AES encrypted entries. */
    internal const val VERSION_AES = 51
    internal const val VERSION_DEFAULT = 20
    internal const val VERSION_ZIP64 = 45
}
