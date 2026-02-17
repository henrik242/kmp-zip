package no.synth.kmplibs.zip

object ZipConstants {
    const val STORED = 0
    const val DEFLATED = 8

    internal const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
    internal const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
}
