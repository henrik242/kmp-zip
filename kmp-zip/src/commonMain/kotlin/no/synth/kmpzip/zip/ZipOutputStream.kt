package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.AesExtraField
import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.crypto.WinZipAesCipher
import no.synth.kmpzip.crypto.ZipCrypto
import no.synth.kmpzip.crypto.secureRandomBytes
import no.synth.kmpzip.io.OutputStream

/**
 * Encryption method for ZIP output.
 */
enum class ZipEncryption {
    /** WinZip AES encryption (strong, recommended). */
    AES,
    /** PKWare traditional encryption (weak, for compatibility). */
    LEGACY,
}

/**
 * Writes ZIP entries to an output stream, with optional encryption.
 *
 * @param output the underlying output stream
 * @param password optional password for encryption (as UTF-8 bytes)
 * @param encryption encryption method: [ZipEncryption.AES] (default) or [ZipEncryption.LEGACY]
 * @param aesStrength AES key strength (default: AES-256, only applies when encryption is AES)
 * @param aesVersion AES version: 1 (AE-1, writes CRC) or 2 (AE-2, CRC=0)
 */
class ZipOutputStream(
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
        if (closed) throw Exception("Stream closed")
        if (currentEntry != null) closeEntry()

        val method = if (entry.method == -1) defaultMethod else entry.method
        entry.method = method

        entryOffset = bytesWritten
        entryCrc = PlatformCrc32()
        entryUncompressedSize = 0
        entryCompressedSize = 0

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
            // Legacy (ZipCrypto) encrypted entry
            if (method == ZipConstants.DEFLATED) {
                // Buffer deflated data so we can write sizes in the header
                deflater = PlatformDeflater().also { it.init(level) }
                compressedBuffer = mutableListOf()
                bufferedMethod = method // reuse field to track actual method
            } else {
                // STORED: stream directly (sizes known upfront)
                // Adjust compressedSize to include 12-byte encryption header
                entry.compressedSize = entry.compressedSize + ZipCrypto.ENCRYPTION_HEADER_SIZE
                val flag = 0x01
                writeLocalFileHeader(entry, method, flag, ZipConstants.VERSION_DEFAULT)

                val cipher = ZipCrypto(password)
                val checkByte = if (entry.crc != -1L) {
                    ((entry.crc ushr 24) and 0xFF).toInt()
                } else {
                    ((entry.time ushr 8) and 0xFF).toInt()
                }
                val encHeader = ZipCrypto.createEncryptionHeader(cipher, checkByte)
                writeRaw(encHeader, 0, encHeader.size)
                entryCompressedSize += ZipCrypto.ENCRYPTION_HEADER_SIZE
                legacyCipher = cipher
            }
        } else {
            // Non-encrypted: write header immediately, streaming mode
            val flag = when (method) {
                ZipConstants.STORED -> {
                    if (entry.size == -1L || entry.crc == -1L) {
                        throw Exception("STORED entry requires size and crc to be set")
                    }
                    0
                }
                ZipConstants.DEFLATED -> {
                    deflater = PlatformDeflater().also { it.init(level) }
                    0x08 // data descriptor flag
                }
                else -> throw Exception("Unsupported compression method: $method")
            }

            writeLocalFileHeader(entry, method, flag, ZipConstants.VERSION_DEFAULT)
        }

        currentEntry = entry
    }

    private fun writeLocalFileHeader(entry: ZipEntry, method: Int, flag: Int, version: Int) {
        val nameBytes = entry.name.encodeToByteArray()
        val extra = entry.extra

        writeLeInt(ZipConstants.LOCAL_FILE_HEADER_SIGNATURE)
        writeLeShort(version) // version needed to extract
        writeLeShort(flag) // general purpose bit flag
        writeLeShort(method) // compression method
        writeLeShort((entry.time and 0xFFFF).toInt()) // last mod file time
        writeLeShort(((entry.time ushr 16) and 0xFFFF).toInt()) // last mod file date
        if (flag and 0x08 != 0) {
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
        writeLeShort(extra?.size ?: 0) // extra field length
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
        if (closed) throw Exception("Stream closed")
        val entry = currentEntry ?: throw Exception("No current entry")
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
            )
        )

        if (flag and 0x08 != 0) {
            // Write data descriptor
            writeLeInt(ZipConstants.DATA_DESCRIPTOR_SIGNATURE)
            writeLeInt(crc.toInt())
            writeLeInt(entryCompressedSize.toInt())
            writeLeInt(entryUncompressedSize.toInt())
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
        }
    }

    fun finish() {
        if (finished) return
        if (closed) throw Exception("Stream closed")
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
    }

    private fun writeCentralDirectoryHeader(info: EntryInfo) {
        val entry = info.entry
        val nameBytes = entry.name.encodeToByteArray()
        val extra = entry.extra
        val commentBytes = entry.comment?.encodeToByteArray()

        writeLeInt(ZipConstants.CENTRAL_DIR_HEADER_SIGNATURE)
        writeLeShort(info.versionNeeded) // version made by
        writeLeShort(info.versionNeeded) // version needed to extract
        writeLeShort(info.flag) // general purpose bit flag
        writeLeShort(info.headerMethod) // compression method
        writeLeShort((entry.time and 0xFFFF).toInt()) // last mod file time
        writeLeShort(((entry.time ushr 16) and 0xFFFF).toInt()) // last mod file date
        writeLeInt(if (info.headerMethod == ZipConstants.AE_ENCRYPTED && aesVersion == 2) {
            0 // AE-2: CRC is 0 in central directory
        } else {
            info.crc.toInt()
        })
        writeLeInt(info.compressedSize.toInt()) // compressed size
        writeLeInt(info.uncompressedSize.toInt()) // uncompressed size
        writeLeShort(nameBytes.size) // file name length
        writeLeShort(extra?.size ?: 0) // extra field length
        writeLeShort(commentBytes?.size ?: 0) // file comment length
        writeLeShort(0) // disk number start
        writeLeShort(0) // internal file attributes
        writeLeInt(0) // external file attributes
        writeLeInt(info.offset.toInt()) // relative offset of local header
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

        writeLeInt(ZipConstants.END_OF_CENTRAL_DIR_SIGNATURE)
        writeLeShort(0) // number of this disk
        writeLeShort(0) // disk where central directory starts
        writeLeShort(entries.size) // number of central directory records on this disk
        writeLeShort(entries.size) // total number of central directory records
        writeLeInt(centralDirSize.toInt()) // size of central directory
        writeLeInt(centralDirOffset.toInt()) // offset of start of central directory
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

    // -- Low-level writing helpers --

    private fun writeRaw(b: ByteArray, off: Int, len: Int) {
        output.write(b, off, len)
        bytesWritten += len
    }

    private fun writeLeShort(v: Int) {
        val buf = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
        )
        writeRaw(buf, 0, 2)
    }

    private fun writeLeInt(v: Int) {
        val buf = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
        writeRaw(buf, 0, 4)
    }
}

/** Convenience constructor with string password (AES encryption). */
fun ZipOutputStream(output: OutputStream, password: String, aesStrength: AesStrength = AesStrength.AES_256): ZipOutputStream {
    return ZipOutputStream(output, password.encodeToByteArray(), aesStrength = aesStrength)
}

/** Convenience constructor with string password and explicit encryption method. */
fun ZipOutputStream(output: OutputStream, password: String, encryption: ZipEncryption): ZipOutputStream {
    return ZipOutputStream(output, password.encodeToByteArray(), encryption = encryption)
}
