package no.synth.kmpzip.zip

/** Fixed-size portion of a central directory header (before name/extra/comment). */
internal const val CD_FIXED_LEN = 46

/** Fixed-size portion of an end-of-central-directory record (before the comment). */
internal const val EOCD_MIN_LEN = 22

/** ZIP64 end-of-central-directory locator. */
internal const val ZIP64_LOCATOR_LEN = 20

/** Fixed-size portion of a ZIP64 end-of-central-directory record. */
internal const val ZIP64_EOCD_MIN_LEN = 56

/** Length of the ZIP64 record's leading signature and size field, not counted in the size. */
internal const val ZIP64_EOCD_SIZE_FIELD_END = 12

/** Digital signature record, which may follow the central directory headers. */
internal const val DIGITAL_SIGNATURE_SIGNATURE = 0x05054b50

/** The fields of an end-of-central-directory record, plain or ZIP64. */
internal class EndOfCentralDirectory(
    val diskNumber: Long,
    val cdStartDisk: Long,
    val diskEntries: Long,
    val totalEntries: Long,
    val cdSize: Long,
    val cdOffset: Long,
)

/** Parses the end-of-central-directory record whose signature is at [p]. */
internal fun parseEndOfCentralDirectory(b: ByteArray, p: Int): EndOfCentralDirectory =
    EndOfCentralDirectory(
        diskNumber = readLeShort(b, p + 4).toLong(),
        cdStartDisk = readLeShort(b, p + 6).toLong(),
        diskEntries = readLeShort(b, p + 8).toLong(),
        totalEntries = readLeShort(b, p + 10).toLong(),
        cdSize = readLeUInt(b, p + 12),
        cdOffset = readLeUInt(b, p + 16),
    )

/** Parses the ZIP64 end-of-central-directory record whose signature is at [p]. */
internal fun parseZip64EndOfCentralDirectory(b: ByteArray, p: Int): EndOfCentralDirectory =
    EndOfCentralDirectory(
        diskNumber = readLeUInt(b, p + 16),
        cdStartDisk = readLeUInt(b, p + 20),
        diskEntries = readLeLong(b, p + 24),
        totalEntries = readLeLong(b, p + 32),
        cdSize = readLeLong(b, p + 40),
        cdOffset = readLeLong(b, p + 48),
    )

/** Formats a record signature for error messages, e.g. `0x04034b50`. */
internal fun Int.toSignatureHex(): String = "0x" + toUInt().toString(16).padStart(8, '0')

/** A parsed central directory header; [end] is the index just past it. */
internal class CentralDirectoryHeader(val entry: ZipEntry, val localHeaderOffset: Long, val end: Int)

/** Total length of the central directory header whose fixed part starts at [p]. */
internal fun centralDirectoryHeaderLength(cd: ByteArray, p: Int): Int =
    CD_FIXED_LEN + readLeShort(cd, p + 28) + readLeShort(cd, p + 30) + readLeShort(cd, p + 32)

/**
 * Parses the central directory header at [p] in [cd], resolving ZIP64 values.
 * The caller has checked the signature.
 */
internal fun parseCentralDirectoryHeader(cd: ByteArray, p: Int): CentralDirectoryHeader {
    if (p + CD_FIXED_LEN > cd.size) throw ZipFormatException("Truncated central directory header")

    val utf8 = readLeShort(cd, p + 8) and UTF8_FLAG != 0
    val method = readLeShort(cd, p + 10)
    val time = readLeShort(cd, p + 12)
    val date = readLeShort(cd, p + 14)
    val crc = readLeUInt(cd, p + 16)
    var compressedSize = readLeUInt(cd, p + 20)
    var uncompressedSize = readLeUInt(cd, p + 24)
    val nameLen = readLeShort(cd, p + 28)
    val extraLen = readLeShort(cd, p + 30)
    val commentLen = readLeShort(cd, p + 32)
    var diskStart = readLeShort(cd, p + 34).toLong()
    var localHeaderOffset = readLeUInt(cd, p + 42)

    val nameStart = p + CD_FIXED_LEN
    val extraStart = nameStart + nameLen
    val commentStart = extraStart + extraLen
    val nextStart = commentStart + commentLen
    if (nextStart > cd.size) throw ZipFormatException("Truncated central directory header")

    val name = decodeZipText(cd, nameStart, extraStart, utf8)
    val extra = if (extraLen > 0) cd.copyOfRange(extraStart, commentStart) else null

    val uMarked = uncompressedSize == ZipConstants.ZIP64_MAGIC
    val cMarked = compressedSize == ZipConstants.ZIP64_MAGIC
    val offsetMarked = localHeaderOffset == ZipConstants.ZIP64_MAGIC
    val diskMarked = diskStart == ZipConstants.ZIP64_MAGIC_COUNT.toLong()
    if (uMarked || cMarked || offsetMarked || diskMarked) {
        val zip64Extra = Zip64Extra.find(extra)
            ?: throw ZipFormatException("Missing ZIP64 extra field for entry: $name")
        uncompressedSize = zip64Extra.next(uncompressedSize, uMarked)
        compressedSize = zip64Extra.next(compressedSize, cMarked)
        localHeaderOffset = zip64Extra.next(localHeaderOffset, offsetMarked)
        diskStart = zip64Extra.nextInt(diskStart, diskMarked)
    }
    if (diskStart != 0L) {
        throw ZipUnsupportedFeatureException("Split/spanned ZIP archives are not supported")
    }
    val comment = if (commentLen > 0) decodeZipText(cd, commentStart, nextStart, utf8) else null

    val dosTime = (date.toLong() shl 16) or time.toLong()

    // Mirror ZipInputStream: report the actual compression method for AES entries.
    val effectiveMethod = if (method == ZipConstants.AE_ENCRYPTED) {
        parseAesExtraFieldOrThrow(extra)?.actualCompressionMethod ?: method
    } else {
        method
    }

    val entry = ZipEntry(
        name = name,
        size = uncompressedSize,
        compressedSize = compressedSize,
        crc = crc,
        method = effectiveMethod,
        time = dosTime,
        extra = extra,
    )
    entry.comment = comment
    return CentralDirectoryHeader(entry, localHeaderOffset, nextStart)
}
