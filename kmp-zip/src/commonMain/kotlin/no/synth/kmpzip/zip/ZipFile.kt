package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.AesExtraField
import no.synth.kmpzip.io.ByteArraySeekableSource
import no.synth.kmpzip.io.Closeable
import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.SeekableSource
import no.synth.kmpzip.io.SeekableSourceInputStream

/**
 * Random-access reader for ZIP archives.
 *
 * Unlike [ZipInputStream], which walks entries sequentially from the front of the
 * stream, [ZipFile] reads the **central directory** at the end of the archive once,
 * then seeks straight to any entry's local header on demand. That makes "open the
 * archive, extract this one file now" cheap even for large archives, without
 * inflating or decrypting anything you don't ask for.
 *
 * Entry extraction reuses [ZipInputStream] over a positioned view of the source, so
 * decompression and decryption (WinZip AES and PKWare traditional) behave exactly as
 * they do for sequential reads.
 *
 * ```kotlin
 * // File-backed: only the central directory and the entry you read touch the disk —
 * // the archive is never fully loaded into memory.
 * ZipFile(fileSeekableSource("/path/to/backup.zip"), password = "secret").use { zip ->
 *     val db = zip.getEntry("diary.sqlite") ?: error("missing db")
 *     zip.getInputStream(db).use { it.readBytes() }   // only this entry is read
 * }
 * ```
 *
 * In a browser (wasmJs) there is no synchronous file access — back it with
 * [ByteArraySeekableSource] (or `ZipFile(bytes, …)`) instead.
 *
 * **Not supported:** ZIP64 archives (entries or directories that require 64-bit
 * offsets/sizes). Opening one throws.
 *
 * **Decompression-bomb safety.** As with [ZipInputStream], per-entry uncompressed
 * size is not capped — check [ZipEntry.size] before reading, or read against a budget.
 *
 * @param source the archive bytes, accessed by absolute position
 * @param password optional password for decrypting encrypted entries (as UTF-8 bytes)
 */
class ZipFile(
    private val source: SeekableSource,
    private val password: ByteArray? = null,
) : Closeable {

    private val localHeaderOffsets: Map<String, Long>

    /** Every entry in the archive, in central-directory order. */
    val entries: List<ZipEntry>

    private var closed = false

    init {
        val parsed = readCentralDirectory()
        entries = parsed.map { it.entry }
        val offsets = LinkedHashMap<String, Long>(parsed.size)
        for (e in parsed) {
            // First occurrence wins for duplicate names.
            if (e.entry.name !in offsets) offsets[e.entry.name] = e.localHeaderOffset
        }
        localHeaderOffsets = offsets
    }

    /** Returns the entry with the given [name], or `null` if no such entry exists. */
    fun getEntry(name: String): ZipEntry? = entries.firstOrNull { it.name == name }

    /**
     * Opens a stream over the (decompressed, decrypted) contents of [entry].
     *
     * The returned stream reads only this entry; closing it does not close the
     * [ZipFile]. Several entries may be open at once.
     */
    fun getInputStream(entry: ZipEntry): InputStream = getInputStream(entry.name)

    /**
     * Opens a stream over the entry named [name].
     *
     * @throws IllegalArgumentException if no entry with that name exists.
     */
    fun getInputStream(name: String): InputStream {
        if (closed) throw IllegalStateException("ZipFile closed")
        val offset = localHeaderOffsets[name]
            ?: throw IllegalArgumentException("No such entry: $name")
        val view = SeekableSourceInputStream(source, offset)
        val zis = ZipInputStream(view, password)
        zis.nextEntry ?: throw Exception("No local file header at offset $offset for entry: $name")
        return zis
    }

    override fun close() {
        if (!closed) {
            closed = true
            source.close()
        }
    }

    private class CdEntry(val entry: ZipEntry, val localHeaderOffset: Long)

    private fun readCentralDirectory(): List<CdEntry> {
        val sourceSize = source.size
        val eocd = findEndOfCentralDirectory(sourceSize)

        // EOCD fixed fields: this-disk @4, cd-start-disk @6, total-records @10,
        // cd-size @12, cd-offset @16, comment-length @20.
        val diskNumber = readLeShort(eocd.bytes, eocd.offset + 4)
        val cdStartDisk = readLeShort(eocd.bytes, eocd.offset + 6)
        val totalEntries = readLeShort(eocd.bytes, eocd.offset + 10)
        val cdSize = readLeUInt(eocd.bytes, eocd.offset + 12)
        val cdOffset = readLeUInt(eocd.bytes, eocd.offset + 16)

        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || totalEntries == 0xFFFF) {
            throw Exception("ZIP64 archives are not supported")
        }
        if (diskNumber != 0 || cdStartDisk != 0) {
            throw Exception("Split/spanned ZIP archives are not supported")
        }
        if (cdOffset + cdSize > sourceSize) {
            throw Exception("Central directory extends past end of archive")
        }

        val cd = readFully(cdOffset, cdSize.toInt())

        val result = ArrayList<CdEntry>(totalEntries)
        var p = 0
        while (p + 4 <= cd.size && readLeUInt(cd, p) == CENTRAL_DIR_HEADER_SIG) {
            if (p + CD_FIXED_LEN > cd.size) throw Exception("Truncated central directory header")

            val method = readLeShort(cd, p + 10)
            val time = readLeShort(cd, p + 12)
            val date = readLeShort(cd, p + 14)
            val crc = readLeUInt(cd, p + 16)
            val compressedSize = readLeUInt(cd, p + 20)
            val uncompressedSize = readLeUInt(cd, p + 24)
            val nameLen = readLeShort(cd, p + 28)
            val extraLen = readLeShort(cd, p + 30)
            val commentLen = readLeShort(cd, p + 32)
            val localHeaderOffset = readLeUInt(cd, p + 42)

            val nameStart = p + CD_FIXED_LEN
            val extraStart = nameStart + nameLen
            val commentStart = extraStart + extraLen
            val nextStart = commentStart + commentLen
            if (nextStart > cd.size) throw Exception("Truncated central directory header")

            if (localHeaderOffset == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL ||
                uncompressedSize == 0xFFFFFFFFL
            ) {
                throw Exception("ZIP64 archives are not supported")
            }

            val name = cd.decodeToString(nameStart, extraStart)
            val extra = if (extraLen > 0) cd.copyOfRange(extraStart, commentStart) else null
            val comment = if (commentLen > 0) cd.decodeToString(commentStart, nextStart) else null

            val dosTime = (date.toLong() shl 16) or time.toLong()

            // Mirror ZipInputStream: report the actual compression method for AES entries.
            val effectiveMethod = if (method == ZipConstants.AE_ENCRYPTED) {
                AesExtraField.parse(extra)?.actualCompressionMethod ?: method
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

            result.add(CdEntry(entry, localHeaderOffset))
            p = nextStart
        }

        // The signature loop stops at the first non-header word. Fail loudly only when
        // we found *fewer* records than the EOCD declared (truncated/corrupt directory).
        // Finding more is tolerated: an archive with > 65535 entries written without
        // ZIP64 stores a wrapped 16-bit count, and re-appended archives carry a stale
        // count — both open fine in java.util.zip/libzip, so we don't reject them.
        if (result.size < totalEntries) {
            throw Exception(
                "Truncated central directory: expected $totalEntries entries, found ${result.size}"
            )
        }

        return result
    }

    private class Eocd(val bytes: ByteArray, val offset: Int)

    private fun findEndOfCentralDirectory(sourceSize: Long): Eocd {
        if (sourceSize < EOCD_MIN_LEN) throw Exception("Not a ZIP file: too small")

        // The EOCD is at most EOCD_MIN_LEN + 65535 (max comment) bytes from the end.
        val tailLen = minOf(sourceSize, (EOCD_MIN_LEN + 0xFFFF).toLong()).toInt()
        val tailStart = sourceSize - tailLen
        val tail = readFully(tailStart, tailLen)

        // Scan backwards for the signature; the last match is the real EOCD.
        for (i in tail.size - EOCD_MIN_LEN downTo 0) {
            if (readLeUInt(tail, i) == END_OF_CENTRAL_DIR_SIG) {
                // Validate the comment length lands exactly at end of file.
                val commentLen = readLeShort(tail, i + 20)
                if (i + EOCD_MIN_LEN + commentLen == tail.size) {
                    return Eocd(tail, i)
                }
            }
        }
        throw Exception("Not a ZIP file: end-of-central-directory record not found")
    }

    /** Reads exactly [length] bytes starting at [position], failing on short reads. */
    private fun readFully(position: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = source.read(position + off, buf, off, length - off)
            when {
                n < 0 -> throw Exception("Unexpected end of source while reading $length bytes at $position")
                // A 0 here violates the SeekableSource.read contract (only legal for a
                // zero-length request) — surface it as such rather than as truncation.
                n == 0 -> throw Exception("SeekableSource.read returned 0 for a non-empty request at ${position + off}")
            }
            off += n
        }
        return buf
    }

    private companion object {
        // readLeUInt yields a Long; compare against the unsigned signatures as Longs.
        val CENTRAL_DIR_HEADER_SIG = ZipConstants.CENTRAL_DIR_HEADER_SIGNATURE.toLong()
        val END_OF_CENTRAL_DIR_SIG = ZipConstants.END_OF_CENTRAL_DIR_SIGNATURE.toLong()

        /** Fixed-size portion of a central directory header (before name/extra/comment). */
        const val CD_FIXED_LEN = 46

        /** Fixed-size portion of an end-of-central-directory record (before comment). */
        const val EOCD_MIN_LEN = 22
    }
}

/** Convenience constructor for reading a ZIP from a byte array. */
fun ZipFile(data: ByteArray, password: ByteArray? = null): ZipFile =
    ZipFile(ByteArraySeekableSource(data), password)

/** Convenience constructor with string password. */
fun ZipFile(source: SeekableSource, password: String): ZipFile =
    ZipFile(source, password.encodeToByteArray())

/** Convenience constructor for reading a ZIP from a byte array with string password. */
fun ZipFile(data: ByteArray, password: String): ZipFile =
    ZipFile(ByteArraySeekableSource(data), password.encodeToByteArray())
