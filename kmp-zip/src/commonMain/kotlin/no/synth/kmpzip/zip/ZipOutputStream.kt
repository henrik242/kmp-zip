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
 * directory handle that, but some streaming readers do not. Encrypted entries are
 * buffered in memory and so are limited to 2 GiB.
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

    // AES encryption state
    private var aesCipher: WinZipAesCipher? = null
    private var aesSalt: ByteArray? = null

    // Buffering state — used by AES and legacy DEFLATED entries that buffer compressed data
    private var bufferedMethod: Int = -1 // actual compression method (STORED/DEFLATED) for buffered entries
    private var compressedBuffer: MutableList<ByteArray>? = null

    // Legacy (ZipCrypto) encryption state
    private var legacyCipher: ZipCrypto? = null

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
        // The extra field length is 16 bits. Check before any data is written, with room
        // for the fields added later: ZIP64 in the central directory, AES at closeEntry.
        val reserved = ZIP64_CD_EXTRA_MAX_SIZE +
            if (password != null && encryption == ZipEncryption.AES) AesExtraField.TOTAL_SIZE else 0
        val userExtraSize = stripZip64Extra(entry.extra)?.size ?: 0
        require(userExtraSize <= MAX_EXTRA_SIZE - reserved) {
            "Extra field too long: $userExtraSize bytes, at most ${MAX_EXTRA_SIZE - reserved} allowed"
        }
        entry.method = method

        entryOffset = bytesWritten
        entryCrc = PlatformCrc32()
        entryUncompressedSize = 0
        entryCompressedSize = 0
        entryZip64 = false

        val isEncrypted = password != null

        if (isEncrypted && encryption == ZipEncryption.AES) {
            // AES encrypted entry: buffer compressed data so we know size for header
            bufferedMethod = method
            val salt = secureRandomBytes(aesStrength.saltLength)
            aesSalt = salt
            val cipher = WinZipAesCipher(password, salt, aesStrength)
            aesCipher = cipher
            compressedBuffer = mutableListOf()

            if (method == ZipConstants.DEFLATED) {
                deflater = PlatformDeflater().also { it.init(level) }
            }

            // Don't write header yet — we'll write everything at closeEntry()
        } else if (isEncrypted && encryption == ZipEncryption.LEGACY) {
            // Legacy (ZipCrypto) encrypted entry — buffer (deflated or raw) so
            // the header can be written after the CRC and final sizes are known.
            if (method == ZipConstants.DEFLATED) {
                deflater = PlatformDeflater().also { it.init(level) }
            }
            compressedBuffer = mutableListOf()
            bufferedMethod = method
        } else {
            // Non-encrypted: write header immediately, streaming mode
            val flag = when (method) {
                ZipConstants.STORED -> {
                    if (entry.size == -1L || entry.crc == -1L) {
                        throw IllegalArgumentException("STORED entry requires size and crc to be set")
                    }
                    // Stored data is its own compressed form. An unset compressed size
                    // would otherwise reach the header as -1, the ZIP64 marker.
                    if (entry.compressedSize == -1L) entry.compressedSize = entry.size
                    0
                }
                ZipConstants.DEFLATED -> {
                    deflater = PlatformDeflater().also { it.init(level) }
                    0x08 // data descriptor flag
                }
                else -> throw IllegalArgumentException("Unsupported compression method: $method")
            }

            entryZip64 = entry.size >= ZipConstants.ZIP64_MAGIC || entry.compressedSize >= ZipConstants.ZIP64_MAGIC
            writeLocalFileHeader(entry, method, flag, ZipConstants.VERSION_DEFAULT, entryZip64)
        }

        currentEntry = entry
    }

    private fun writeLocalFileHeader(entry: ZipEntry, method: Int, flag: Int, version: Int, zip64: Boolean = false) {
        val nameBytes = entry.name.encodeToByteArray()
        val hasDescriptor = flag and 0x08 != 0
        val userExtra = stripZip64Extra(entry.extra)
        val extra = if (zip64) {
            // The local ZIP64 field must hold both sizes; zeros when a descriptor follows.
            val field = if (hasDescriptor) zip64ExtraField(0, 0) else zip64ExtraField(entry.size, entry.compressedSize)
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
            writeLeInt(if (hasDescriptor) 0 else entry.crc.toInt()) // crc-32
            writeLeInt(-1) // compressed size, in the ZIP64 field
            writeLeInt(-1) // uncompressed size, in the ZIP64 field
        } else if (hasDescriptor) {
            // Data descriptor follows, write zeros
            writeLeInt(0) // crc-32
            writeLeInt(0) // compressed size
            writeLeInt(0) // uncompressed size
        } else {
            writeLeInt(entry.crc.toInt()) // crc-32
            writeLeInt(entry.compressedSize.toInt()) // compressed size
            writeLeInt(entry.size.toInt()) // uncompressed size
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

        val method = if (aesCipher != null) bufferedMethod else entry.method
        when (method) {
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

        while (remaining > 0) {
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
            // Neither side advanced, so another pass would do the same thing forever.
            if (result.bytesConsumed == 0 && result.bytesProduced == 0) {
                throw NoProgressException("Deflater made no progress on entry: ${currentEntry?.name}")
            }
        }
    }

    fun closeEntry() {
        val entry = currentEntry ?: return

        if (aesCipher != null) {
            closeEncryptedEntry(entry)
        } else if (compressedBuffer != null && legacyCipher == null && password != null && encryption == ZipEncryption.LEGACY) {
            closeLegacyBufferedEntry(entry)
        } else {
            closeNonEncryptedEntry(entry)
        }

        deflater?.end()
        deflater = null
        currentEntry = null
    }

    /** Finish deflation (if needed) and collect all buffered data. */
    private fun finishAndCollectBufferedData(): ByteArray {
        if (bufferedMethod == ZipConstants.DEFLATED) {
            finishDeflate()
        }
        val data = collectBufferedData()
        compressedBuffer = null
        return data
    }

    private fun closeLegacyBufferedEntry(entry: ZipEntry) {
        val compressedData = finishAndCollectBufferedData()
        val crc = entryCrc.getValue()

        // Initialize cipher with CRC check byte for verification
        val cipher = ZipCrypto(checkNotNull(password))
        val checkByte = ((crc ushr 24) and 0xFF).toInt()
        val encHeader = ZipCrypto.createEncryptionHeader(cipher, checkByte)

        // Encrypt the compressed data
        val encryptedData = compressedData.copyOf()
        cipher.encrypt(encryptedData, 0, encryptedData.size)

        val totalCompressedSize = (ZipCrypto.ENCRYPTION_HEADER_SIZE + encryptedData.size).toLong()

        // Write local header with known sizes
        entry.crc = crc
        entry.compressedSize = totalCompressedSize
        entry.size = entryUncompressedSize
        val flag = 0x01 // encrypted, no data descriptor
        writeLocalFileHeader(entry, entry.method, flag, ZipConstants.VERSION_DEFAULT)

        // Write encryption header + encrypted data
        writeRaw(encHeader, 0, encHeader.size)
        writeRaw(encryptedData, 0, encryptedData.size)

        entries.add(
            EntryInfo(
                entry = entry,
                offset = entryOffset,
                crc = crc,
                compressedSize = totalCompressedSize,
                uncompressedSize = entryUncompressedSize,
                flag = flag,
                headerMethod = entry.method,
                versionNeeded = ZipConstants.VERSION_DEFAULT,
            )
        )
    }

    /** Emit compressed bytes: buffer for AES/legacy-DEFLATED, encrypt-and-write for legacy-STORED, or write directly. */
    private fun emitCompressed(b: ByteArray, off: Int, len: Int) {
        val buf = compressedBuffer
        val cipher = legacyCipher
        if (buf != null) {
            buf.add(b.copyOfRange(off, off + len))
        } else if (cipher != null) {
            val encrypted = b.copyOfRange(off, off + len)
            cipher.encrypt(encrypted, 0, encrypted.size)
            writeRaw(encrypted, 0, encrypted.size)
        } else {
            writeRaw(b, off, len)
        }
    }

    private fun closeNonEncryptedEntry(entry: ZipEntry) {
        if (entry.method == ZipConstants.DEFLATED) {
            finishDeflate()
        }

        val crc = entryCrc.getValue()
        val isLegacy = legacyCipher != null
        legacyCipher = null
        val encryptFlag = if (isLegacy) 0x01 else 0
        val flag = encryptFlag or (if (entry.method == ZipConstants.DEFLATED) 0x08 else 0)

        entries.add(
            EntryInfo(
                entry = entry,
                offset = entryOffset,
                crc = crc,
                compressedSize = entryCompressedSize,
                uncompressedSize = entryUncompressedSize,
                flag = flag,
                headerMethod = entry.method,
                versionNeeded = ZipConstants.VERSION_DEFAULT,
                zip64Local = entryZip64,
            )
        )

        if (flag and 0x08 != 0) {
            // Write data descriptor, with 8-byte sizes if the header announced ZIP64
            // or the entry outgrew 32 bits after the header was written.
            val zip64 = entryZip64 ||
                entryCompressedSize >= ZipConstants.ZIP64_MAGIC ||
                entryUncompressedSize >= ZipConstants.ZIP64_MAGIC
            writeLeInt(ZipConstants.DATA_DESCRIPTOR_SIGNATURE)
            writeLeInt(crc.toInt())
            if (zip64) {
                writeLeLong(entryCompressedSize)
                writeLeLong(entryUncompressedSize)
            } else {
                writeLeInt(entryCompressedSize.toInt())
                writeLeInt(entryUncompressedSize.toInt())
            }
        }
    }

    private fun closeEncryptedEntry(entry: ZipEntry) {
        val cipher = checkNotNull(aesCipher)
        val salt = checkNotNull(aesSalt)

        val compressedData = finishAndCollectBufferedData()
        val crc = entryCrc.getValue()

        // Encrypt the compressed data
        val encryptedData = ByteArray(compressedData.size)
        cipher.encrypt(compressedData, 0, encryptedData, 0, compressedData.size)
        val authCode = cipher.getAuthCode()

        // Total compressed size in the header includes salt + pvv + encrypted data + auth code
        val totalCompressedSize = salt.size.toLong() + 2 + encryptedData.size.toLong() +
            WinZipAesCipher.AUTH_CODE_LENGTH

        // Create the AES extra field
        val aesExtra = AesExtraField.create(
            version = aesVersion,
            strength = aesStrength,
            actualCompressionMethod = bufferedMethod,
        )

        // Merge AES extra field with any existing extra data
        val existingExtra = entry.extra
        val mergedExtra = if (existingExtra != null) {
            existingExtra + aesExtra
        } else {
            aesExtra
        }
        entry.extra = mergedExtra

        // Set entry metadata for the header
        val headerCrc = if (aesVersion == 1) crc else 0L
        entry.crc = headerCrc
        entry.compressedSize = totalCompressedSize
        entry.size = entryUncompressedSize

        val flag = 0x01 // encrypted flag
        val headerMethod = ZipConstants.AE_ENCRYPTED

        // Now write the local file header
        writeLocalFileHeader(entry, headerMethod, flag, ZipConstants.VERSION_AES)

        // Write salt, password verification, encrypted data, auth code
        writeRaw(salt, 0, salt.size)
        writeRaw(cipher.passwordVerification, 0, 2)
        writeRaw(encryptedData, 0, encryptedData.size)
        writeRaw(authCode, 0, authCode.size)

        entries.add(
            EntryInfo(
                entry = entry,
                offset = entryOffset,
                crc = crc, // real CRC for central directory (always stored)
                compressedSize = totalCompressedSize,
                uncompressedSize = entryUncompressedSize,
                flag = flag,
                headerMethod = headerMethod,
                versionNeeded = ZipConstants.VERSION_AES,
            )
        )

        aesCipher = null
        aesSalt = null
    }

    private fun collectBufferedData(): ByteArray {
        val buffer = compressedBuffer ?: return ByteArray(0)
        var totalSize = 0
        for (chunk in buffer) totalSize += chunk.size
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in buffer) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
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
        check(size <= MAX_EXTRA_SIZE) { "Extra field too long: $size bytes" }
        return size
    }

    private companion object {
        const val MAX_EXTRA_SIZE = 0xFFFF

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
