package no.synth.kmpzip.zip

import kotlin.jvm.JvmOverloads
import no.synth.kmpzip.crypto.AesExtraField
import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.crypto.WinZipAesCipher
import no.synth.kmpzip.crypto.ZipCrypto
import no.synth.kmpzip.crypto.secureRandomBytes
import no.synth.kmpzip.io.NoProgressException
import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.io.checkBounds

/**
 * Encryption method for ZIP output.
 */
enum class ZipEncryption {
    /** WinZip AES encryption (strong, recommended). */
    AES,
    /** PKWare traditional encryption (weak, for compatibility). */
    LEGACY,
}

// An unset timestamp must not reach the archive: 0xFFFF/0xFFFF is not a valid DOS
// date, and java.util.zip reads it back as the year 2108.
private val ZipEntry.dosTime: Long
    get() = if (time == -1L) ZipConstants.DOS_EPOCH else time

/** A 32-bit header value, or the ZIP64 marker when it does not fit. */
private fun zip64Field(value: Long): Int =
    if (value >= ZipConstants.ZIP64_MAGIC) -1 else value.toInt()

/**
 * Writes ZIP entries to an output stream, with optional encryption.
 *
 * ZIP64 records are written only where needed: for entries or offsets of 4 GiB or
 * more, and for archives of 65535 entries or more. Set [ZipEntry.size] before
 * [putNextEntry] when a DEFLATED entry is known to be that large, so its local header
 * carries ZIP64 sizes. Without it, an entry that only turns out to be that large gets
 * a ZIP64 data descriptor, as java.util.zip writes it; readers that use the central
 * directory handle that, but some streaming readers do not.
 *
 * Entries are written as they come, encrypted ones included. A STORED entry carries its
 * sizes in the local header ([ZipEntry.size] and [ZipEntry.crc] must be set, except that
 * an encrypted STORED entry may leave them unset, and an AE-2 one needs only the size).
 * Any other entry is followed by a data descriptor. [ZipEntry.size], [ZipEntry.crc] and
 * [ZipEntry.compressedSize] are set from the written data when the entry is closed.
 *
 * @param output the underlying output stream
 * @param password optional password for encryption (as UTF-8 bytes)
 * @param encryption encryption method: [ZipEncryption.AES] (default) or [ZipEncryption.LEGACY]
 * @param aesStrength AES key strength (default: AES-256, only applies when encryption is AES)
 * @param aesVersion AES version: 1 (AE-1, writes CRC) or 2 (AE-2, CRC=0)
 */
class ZipOutputStream @JvmOverloads constructor(
    private val output: OutputStream,
    private val password: ByteArray? = null,
    private val encryption: ZipEncryption = ZipEncryption.AES,
    private val aesStrength: AesStrength = AesStrength.AES_256,
    private val aesVersion: Int = 2,
) : OutputStream() {
    private var closed = false
    private var finished = false
    private var currentEntry: ZipEntry? = null
    private var defaultMethod = ZipConstants.DEFLATED
    private var level = -1
    private var comment: String? = null

    // Track entries for central directory
    private val entries = mutableListOf<EntryInfo>()
    private var bytesWritten: Long = 0

    // Per-entry state
    private var entryCrc = PlatformCrc32()
    private var entryUncompressedSize: Long = 0
    private var entryCompressedSize: Long = 0
    private var entryOffset: Long = 0
    private var entryZip64 = false
    private var deflater: PlatformDeflater? = null
    private val deflateBuf = ByteArray(8192)

    // Encryption state for the current entry
    private var aesCipher: WinZipAesCipher? = null
    private var legacyCipher: ZipCrypto? = null
    private val cryptBuf = ByteArray(8192)

    // How the current entry is framed, decided by putNextEntry
    private var entryFlag = 0
    private var entryHeaderMethod = 0
    private var entryVersion = ZipConstants.VERSION_DEFAULT
    // Encryption bytes around the data (AES salt, check value and auth code, or the
    // ZipCrypto header), counted in the compressed size.
    private var entryOverhead = 0L
    // Sizes in the local header and no data descriptor; only a STORED entry knows them.
    private var entrySizesInHeader = false

    private class EntryInfo(
        val entry: ZipEntry,
        val offset: Long,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val flag: Int,
        val headerMethod: Int,
        val versionNeeded: Int,
        val zip64Local: Boolean = false,
    )

    fun setComment(comment: String?) {
        val size = comment?.encodeToByteArray()?.size ?: 0
        require(size <= MAX_FIELD_SIZE) { "ZIP comment too long: $size bytes, at most $MAX_FIELD_SIZE allowed" }
        this.comment = comment
    }

    fun setMethod(method: Int) {
        require(method == ZipConstants.STORED || method == ZipConstants.DEFLATED) {
            "Unsupported compression method: $method"
        }
        defaultMethod = method
    }

    fun setLevel(level: Int) {
        require(level in -1..9) { "Invalid compression level: $level" }
        this.level = level
    }

    fun putNextEntry(entry: ZipEntry) {
        if (closed) throw IllegalStateException("Stream closed")
        if (currentEntry != null) closeEntry()

        val method = if (entry.method == -1) defaultMethod else entry.method
        // ZipEntry.method is public and can be set directly, bypassing setMethod. Validate
        // here, before the encryption branches, so an unsupported method fails loudly
        // instead of matching no write() branch and silently emitting an empty entry.
        require(method == ZipConstants.STORED || method == ZipConstants.DEFLATED) {
            "Unsupported compression method: $method"
        }
        // Name, comment and extra field lengths are 16 bits. Check before any data is
        // written, leaving room in the extra field for what is added later: ZIP64 in the
        // central directory, AES at closeEntry.
        val nameSize = entry.name.encodeToByteArray().size
        require(nameSize <= MAX_FIELD_SIZE) { "Entry name too long: $nameSize bytes, at most $MAX_FIELD_SIZE allowed" }
        val commentSize = entry.comment?.encodeToByteArray()?.size ?: 0
        require(commentSize <= MAX_FIELD_SIZE) { "Entry comment too long: $commentSize bytes, at most $MAX_FIELD_SIZE allowed" }
        val reserved = ZIP64_CD_EXTRA_MAX_SIZE +
            if (password != null && encryption == ZipEncryption.AES) AesExtraField.TOTAL_SIZE else 0
        val userExtraSize = stripZip64Extra(entry.extra)?.size ?: 0
        require(userExtraSize <= MAX_FIELD_SIZE - reserved) {
            "Extra field too long: $userExtraSize bytes, at most ${MAX_FIELD_SIZE - reserved} allowed"
        }
        entry.method = method

        entryOffset = bytesWritten
        entryCrc = PlatformCrc32()
        entryUncompressedSize = 0
        entryCompressedSize = 0
        entryZip64 = false

        val aes = password != null && encryption == ZipEncryption.AES
        val legacy = password != null && encryption == ZipEncryption.LEGACY
        if (method == ZipConstants.STORED && password == null && (entry.size == -1L || entry.crc == -1L)) {
            throw IllegalArgumentException("STORED entry requires size and crc to be set")
        }
        // AE-2 stores no CRC, so an AES entry only needs its size to be known up front.
        entrySizesInHeader = method == ZipConstants.STORED && entry.size >= 0 &&
            (entry.crc >= 0 || (aes && aesVersion == 2))
        entryOverhead = when {
            aes -> aesStrength.saltLength.toLong() + 2 + WinZipAesCipher.AUTH_CODE_LENGTH
            legacy -> ZipCrypto.ENCRYPTION_HEADER_SIZE.toLong()
            else -> 0L
        }
        entryHeaderMethod = if (aes) ZipConstants.AE_ENCRYPTED else method
        entryVersion = if (aes) ZipConstants.VERSION_AES else ZipConstants.VERSION_DEFAULT
        entryFlag = (if (password != null) 0x01 else 0) or (if (entrySizesInHeader) 0 else 0x08)

        if (aes) {
            val aesExtra = AesExtraField.create(version = aesVersion, strength = aesStrength, actualCompressionMethod = method)
            val userExtra = stripExtraField(entry.extra, AesExtraField.HEADER_ID)
            entry.extra = if (userExtra != null) userExtra + aesExtra else aesExtra
        }
        if (method == ZipConstants.DEFLATED) {
            deflater = PlatformDeflater().also { it.init(level) }
        }

        val compressedSize = if (entrySizesInHeader) entry.size + entryOverhead else 0L
        val headerCrc = if (!entrySizesInHeader || (aes && aesVersion == 2)) 0L else entry.crc
        entryZip64 = entry.size >= ZipConstants.ZIP64_MAGIC || compressedSize >= ZipConstants.ZIP64_MAGIC
        writeLocalFileHeader(entry, entryHeaderMethod, entryFlag, entryVersion, entryZip64, headerCrc, compressedSize, entry.size)

        if (aes) {
            val salt = secureRandomBytes(aesStrength.saltLength)
            val cipher = WinZipAesCipher(checkNotNull(password), salt, aesStrength)
            writeRaw(salt, 0, salt.size)
            writeRaw(cipher.passwordVerification, 0, 2)
            aesCipher = cipher
        } else if (legacy) {
            val cipher = ZipCrypto(checkNotNull(password))
            // The check byte is the CRC's high byte, or with a data descriptor the high
            // byte of the DOS time, since the CRC is not known yet (APPNOTE 6.1.6).
            val checkByte = if (entrySizesInHeader) {
                ((entry.crc ushr 24) and 0xFF).toInt()
            } else {
                ((entry.dosTime ushr 8) and 0xFF).toInt()
            }
            val header = ZipCrypto.createEncryptionHeader(cipher, checkByte)
            writeRaw(header, 0, header.size)
            legacyCipher = cipher
        }

        currentEntry = entry
    }

    private fun writeLocalFileHeader(
        entry: ZipEntry, method: Int, flag: Int, version: Int, zip64: Boolean,
        crc: Long, compressedSize: Long, size: Long,
    ) {
        val nameBytes = entry.name.encodeToByteArray()
        val hasDescriptor = flag and 0x08 != 0
        val userExtra = stripZip64Extra(entry.extra)
        val extra = if (zip64) {
            // The local ZIP64 field must hold both sizes; zeros when a descriptor follows.
            val field = if (hasDescriptor) zip64ExtraField(0, 0) else zip64ExtraField(size, compressedSize)
            if (userExtra != null) field + userExtra else field
        } else {
            userExtra
        }

        writeLeInt(ZipConstants.LOCAL_FILE_HEADER_SIGNATURE)
        writeLeShort(if (zip64) maxOf(version, ZipConstants.VERSION_ZIP64) else version) // version needed to extract
        writeLeShort(flag or UTF8_FLAG) // general purpose bit flag
        writeLeShort(method) // compression method
        writeLeShort((entry.dosTime and 0xFFFF).toInt()) // last mod file time
        writeLeShort(((entry.dosTime ushr 16) and 0xFFFF).toInt()) // last mod file date
        if (zip64) {
            writeLeInt(crc.toInt()) // crc-32
            writeLeInt(-1) // compressed size, in the ZIP64 field
            writeLeInt(-1) // uncompressed size, in the ZIP64 field
        } else if (hasDescriptor) {
            // Data descriptor follows, write zeros
            writeLeInt(0) // crc-32
            writeLeInt(0) // compressed size
            writeLeInt(0) // uncompressed size
        } else {
            writeLeInt(crc.toInt()) // crc-32
            writeLeInt(compressedSize.toInt()) // compressed size
            writeLeInt(size.toInt()) // uncompressed size
        }
        writeLeShort(nameBytes.size) // file name length
        writeLeShort(checkExtraSize(extra)) // extra field length
        writeRaw(nameBytes, 0, nameBytes.size)
        if (extra != null) {
            writeRaw(extra, 0, extra.size)
        }
    }

    override fun write(b: Int) {
        val buf = byteArrayOf(b.toByte())
        write(buf, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkBounds(b, off, len)
        if (closed) throw IllegalStateException("Stream closed")
        val entry = currentEntry ?: throw IllegalStateException("No current entry")
        if (len == 0) return

        entryCrc.update(b, off, len)
        entryUncompressedSize += len

        when (entry.method) {
            ZipConstants.STORED -> {
                emitCompressed(b, off, len)
                entryCompressedSize += len
            }
            ZipConstants.DEFLATED -> deflateData(b, off, len)
        }
    }

    private fun deflateData(b: ByteArray, off: Int, len: Int) {
        val def = deflater ?: return
        var inputOffset = off
        var remaining = len
        var drained = false

        // Once the input is used up, keep drawing while output fills the buffer: pako
        // holds what it has produced until it is drawn, and the entry is streamed.
        while (remaining > 0 || !drained) {
            val result = def.deflate(
                b, inputOffset, remaining,
                deflateBuf, 0, deflateBuf.size,
            )
            inputOffset += result.bytesConsumed
            remaining -= result.bytesConsumed

            if (result.bytesProduced > 0) {
                emitCompressed(deflateBuf, 0, result.bytesProduced)
                entryCompressedSize += result.bytesProduced
            }
            drained = result.bytesProduced < deflateBuf.size
            // Neither side advanced, so another pass would do the same thing forever.
            if (remaining > 0 && result.bytesConsumed == 0 && result.bytesProduced == 0) {
                throw NoProgressException("Deflater made no progress on entry: ${currentEntry?.name}")
            }
        }
    }

    fun closeEntry() {
        val entry = currentEntry ?: return
        // A failed entry is dropped, so close() can still finish the archive.
        try {
            if (entry.method == ZipConstants.DEFLATED) finishDeflate()
            val crc = entryCrc.getValue()
            aesCipher?.let { cipher ->
                val authCode = cipher.getAuthCode()
                writeRaw(authCode, 0, authCode.size)
            }
            val compressedSize = entryCompressedSize + entryOverhead

            if (entrySizesInHeader) {
                // The local header already claims these, so a mismatch would corrupt the archive.
                check(entryUncompressedSize == entry.size) {
                    "Entry ${entry.name}: wrote $entryUncompressedSize bytes, but size was set to ${entry.size}"
                }
                // AE-2 does not write the CRC, and readers report its absence as 0, so an AES
            // entry copied from another archive must not be held to it.
            val crcWritten = !(aesCipher != null && aesVersion == 2)
            check(!crcWritten || entry.crc < 0 || crc == entry.crc) { "Entry ${entry.name}: CRC does not match the crc set" }
            } else {
                // AE-2 leaves the CRC out; the auth code covers integrity.
                val descriptorCrc = if (aesCipher != null && aesVersion == 2) 0L else crc
                writeDataDescriptor(descriptorCrc, compressedSize, entryUncompressedSize)
            }

            entries.add(
                EntryInfo(
                    entry = entry,
                    offset = entryOffset,
                    crc = crc,
                    compressedSize = compressedSize,
                    uncompressedSize = entryUncompressedSize,
                    flag = entryFlag,
                    headerMethod = entryHeaderMethod,
                    versionNeeded = entryVersion,
                    zip64Local = entryZip64,
                )
            )
            entry.size = entryUncompressedSize
            entry.compressedSize = compressedSize
            entry.crc = crc
        } finally {
            deflater?.end()
            deflater = null
            aesCipher = null
            legacyCipher = null
            currentEntry = null
        }
    }

    // Uses 8-byte sizes if the header announced ZIP64 or the entry outgrew 32 bits after
    // the header was written.
    private fun writeDataDescriptor(crc: Long, compressedSize: Long, size: Long) {
        val zip64 = entryZip64 || compressedSize >= ZipConstants.ZIP64_MAGIC || size >= ZipConstants.ZIP64_MAGIC
        writeLeInt(ZipConstants.DATA_DESCRIPTOR_SIGNATURE)
        writeLeInt(crc.toInt())
        if (zip64) {
            writeLeLong(compressedSize)
            writeLeLong(size)
        } else {
            writeLeInt(compressedSize.toInt())
            writeLeInt(size.toInt())
        }
    }

    /** Writes compressed bytes, encrypting them first if the entry is encrypted. */
    private fun emitCompressed(b: ByteArray, off: Int, len: Int) {
        val aes = aesCipher
        val legacy = legacyCipher
        if (aes == null && legacy == null) {
            writeRaw(b, off, len)
            return
        }
        var pos = off
        var left = len
        while (left > 0) {
            val n = minOf(left, cryptBuf.size)
            if (aes != null) {
                aes.encrypt(b, pos, cryptBuf, 0, n)
            } else if (legacy != null) {
                b.copyInto(cryptBuf, 0, pos, pos + n)
                legacy.encrypt(cryptBuf, 0, n)
            }
            writeRaw(cryptBuf, 0, n)
            pos += n
            left -= n
        }
    }

    private fun finishDeflate() {
        val def = deflater ?: return
        val emptyInput = ByteArray(0)

        while (!def.isFinished) {
            val result = def.deflate(
                emptyInput, 0, 0,
                deflateBuf, 0, deflateBuf.size,
                finish = true,
            )
            if (result.bytesProduced > 0) {
                emitCompressed(deflateBuf, 0, result.bytesProduced)
                entryCompressedSize += result.bytesProduced
            }
            if (result.streamEnd) break
            // Finishing with no output and no stream end means the deflater is stuck.
            if (result.bytesProduced == 0) {
                throw NoProgressException("Deflater made no progress finishing entry: ${currentEntry?.name}")
            }
        }
    }

    fun finish() {
        if (finished) return
        if (closed) throw IllegalStateException("Stream closed")
        if (currentEntry != null) closeEntry()
        finished = true

        val centralDirOffset = bytesWritten

        // Write central directory
        for (info in entries) {
            writeCentralDirectoryHeader(info)
        }

        val centralDirSize = bytesWritten - centralDirOffset

        // Write end of central directory record
        writeEndOfCentralDirectory(centralDirOffset, centralDirSize)
        flushBuffer()
    }

    override fun flush() {
        flushBuffer()
        output.flush()
    }

    private fun writeCentralDirectoryHeader(info: EntryInfo) {
        val entry = info.entry
        val nameBytes = entry.name.encodeToByteArray()
        val commentBytes = entry.comment?.encodeToByteArray()
        // The comment can be set after putNextEntry; a too-long one must not be truncated.
        check((commentBytes?.size ?: 0) <= MAX_FIELD_SIZE) { "Entry comment too long: ${entry.name}" }

        // The central ZIP64 field holds only the values whose 32-bit field is marked.
        val zip64Values = ArrayList<Long>(3)
        if (info.uncompressedSize >= ZipConstants.ZIP64_MAGIC) zip64Values.add(info.uncompressedSize)
        if (info.compressedSize >= ZipConstants.ZIP64_MAGIC) zip64Values.add(info.compressedSize)
        if (info.offset >= ZipConstants.ZIP64_MAGIC) zip64Values.add(info.offset)
        val userExtra = stripZip64Extra(entry.extra)
        val extra = if (zip64Values.isEmpty()) {
            userExtra
        } else {
            val field = zip64ExtraField(*zip64Values.toLongArray())
            if (userExtra != null) field + userExtra else field
        }
        val version = if (zip64Values.isNotEmpty() || info.zip64Local) {
            maxOf(info.versionNeeded, ZipConstants.VERSION_ZIP64)
        } else {
            info.versionNeeded
        }

        writeLeInt(ZipConstants.CENTRAL_DIR_HEADER_SIGNATURE)
        writeLeShort(version) // version made by
        writeLeShort(version) // version needed to extract
        writeLeShort(info.flag or UTF8_FLAG) // general purpose bit flag
        writeLeShort(info.headerMethod) // compression method
        writeLeShort((entry.dosTime and 0xFFFF).toInt()) // last mod file time
        writeLeShort(((entry.dosTime ushr 16) and 0xFFFF).toInt()) // last mod file date
        writeLeInt(if (info.headerMethod == ZipConstants.AE_ENCRYPTED && aesVersion == 2) {
            0 // AE-2: CRC is 0 in central directory
        } else {
            info.crc.toInt()
        })
        writeLeInt(zip64Field(info.compressedSize)) // compressed size
        writeLeInt(zip64Field(info.uncompressedSize)) // uncompressed size
        writeLeShort(nameBytes.size) // file name length
        writeLeShort(checkExtraSize(extra)) // extra field length
        writeLeShort(commentBytes?.size ?: 0) // file comment length
        writeLeShort(0) // disk number start
        writeLeShort(0) // internal file attributes
        writeLeInt(0) // external file attributes
        writeLeInt(zip64Field(info.offset)) // relative offset of local header
        writeRaw(nameBytes, 0, nameBytes.size)
        if (extra != null) {
            writeRaw(extra, 0, extra.size)
        }
        if (commentBytes != null) {
            writeRaw(commentBytes, 0, commentBytes.size)
        }
    }

    private fun writeEndOfCentralDirectory(centralDirOffset: Long, centralDirSize: Long) {
        val commentBytes = comment?.encodeToByteArray()
        val count = entries.size

        if (count >= ZipConstants.ZIP64_MAGIC_COUNT ||
            centralDirSize >= ZipConstants.ZIP64_MAGIC ||
            centralDirOffset >= ZipConstants.ZIP64_MAGIC
        ) {
            val recordOffset = bytesWritten
            writeLeInt(ZipConstants.ZIP64_END_OF_CENTRAL_DIR_SIGNATURE)
            writeLeLong(44) // size of the rest of this record
            writeLeShort(ZipConstants.VERSION_ZIP64) // version made by
            writeLeShort(ZipConstants.VERSION_ZIP64) // version needed to extract
            writeLeInt(0) // number of this disk
            writeLeInt(0) // disk where central directory starts
            writeLeLong(count.toLong()) // number of central directory records on this disk
            writeLeLong(count.toLong()) // total number of central directory records
            writeLeLong(centralDirSize) // size of central directory
            writeLeLong(centralDirOffset) // offset of start of central directory

            writeLeInt(ZipConstants.ZIP64_END_OF_CENTRAL_DIR_LOCATOR_SIGNATURE)
            writeLeInt(0) // disk with the ZIP64 end of central directory record
            writeLeLong(recordOffset) // offset of the ZIP64 end of central directory record
            writeLeInt(1) // total number of disks
        }

        writeLeInt(ZipConstants.END_OF_CENTRAL_DIR_SIGNATURE)
        writeLeShort(0) // number of this disk
        writeLeShort(0) // disk where central directory starts
        writeLeShort(minOf(count, ZipConstants.ZIP64_MAGIC_COUNT)) // number of central directory records on this disk
        writeLeShort(minOf(count, ZipConstants.ZIP64_MAGIC_COUNT)) // total number of central directory records
        writeLeInt(zip64Field(centralDirSize)) // size of central directory
        writeLeInt(zip64Field(centralDirOffset)) // offset of start of central directory
        writeLeShort(commentBytes?.size ?: 0) // comment length
        if (commentBytes != null) {
            writeRaw(commentBytes, 0, commentBytes.size)
        }
    }

    override fun close() {
        if (!closed) {
            if (!finished) finish()
            closed = true
            deflater?.end()
            deflater = null
            output.close()
        }
    }

    // Backstop for an extra field changed after putNextEntry validated it.
    private fun checkExtraSize(extra: ByteArray?): Int {
        val size = extra?.size ?: 0
        check(size <= MAX_FIELD_SIZE) { "Extra field too long: $size bytes" }
        return size
    }

    private companion object {
        /** Name, comment and extra field lengths are 16-bit header fields. */
        const val MAX_FIELD_SIZE = 0xFFFF

        /** Central ZIP64 field with uncompressed size, compressed size and offset. */
        const val ZIP64_CD_EXTRA_MAX_SIZE = 4 + 3 * 8
    }

    // -- Low-level writing helpers --

    // Headers are written a field at a time; collect them, and small data writes, so
    // the underlying stream sees few large writes instead of many 2- and 4-byte ones.
    private val outBuf = ByteArray(8192)
    private var outLen = 0

    private fun flushBuffer() {
        if (outLen > 0) {
            output.write(outBuf, 0, outLen)
            outLen = 0
        }
    }

    private fun writeRaw(b: ByteArray, off: Int, len: Int) {
        if (len > outBuf.size - outLen) {
            flushBuffer()
            if (len >= outBuf.size) {
                output.write(b, off, len)
                bytesWritten += len
                return
            }
        }
        b.copyInto(outBuf, outLen, off, off + len)
        outLen += len
        bytesWritten += len
    }

    private fun writeByte(v: Int) {
        if (outLen == outBuf.size) flushBuffer()
        outBuf[outLen++] = v.toByte()
        bytesWritten++
    }

    private fun writeLeShort(v: Int) {
        writeByte(v)
        writeByte(v ushr 8)
    }

    private fun writeLeInt(v: Int) {
        writeLeShort(v)
        writeLeShort(v ushr 16)
    }

    private fun writeLeLong(v: Long) {
        writeLeInt(v.toInt())
        writeLeInt((v ushr 32).toInt())
    }
}

/** Convenience constructor with string password (AES encryption). */
@JvmOverloads
fun ZipOutputStream(output: OutputStream, password: String, aesStrength: AesStrength = AesStrength.AES_256): ZipOutputStream {
    return ZipOutputStream(output, password.encodeToByteArray(), aesStrength = aesStrength)
}

/** Convenience constructor with string password and explicit encryption method. */
fun ZipOutputStream(output: OutputStream, password: String, encryption: ZipEncryption): ZipOutputStream {
    return ZipOutputStream(output, password.encodeToByteArray(), encryption = encryption)
}
