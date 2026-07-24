package no.synth.kmpzip.zip

/**
 * Sniff, not validation: true if [data] starts with a local-file-header or end-of-central-directory
 * signature. A truncated archive matches; archives with prepended data (SFX, PK00) don't, even
 * though [ZipFile] opens them.
 */
fun isZip(data: ByteArray): Boolean {
    if (data.size < 4) return false
    val signature = readLeUInt(data, 0).toInt()
    return signature == ZipConstants.LOCAL_FILE_HEADER_SIGNATURE ||
        signature == ZipConstants.END_OF_CENTRAL_DIR_SIGNATURE
}
