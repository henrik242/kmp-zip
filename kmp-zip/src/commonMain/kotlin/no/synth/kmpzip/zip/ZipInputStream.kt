package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.AesExtraField
import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.crypto.WinZipAesCipher
import no.synth.kmpzip.crypto.ZipCrypto
import no.synth.kmpzip.io.InputStream

/**
 * Reads ZIP entries from an input stream, with optional decryption.
 *
 * Supports both WinZip AES encryption and PKWare traditional (legacy) encryption.
 *
 * @param input the underlying input stream
 * @param password optional password for decrypting encrypted entries (as UTF-8 bytes)
 */
class ZipInputStream(
    private val input: InputStream,
    private val password: ByteArray? = null,
) : InputStream() {
    private var currentEntry: ZipEntry? = null
    private var closed = false
    private var entryEof = true

    // For STORED entries
    private var remainingBytes: Long = 0

    // For DEFLATED entries
    private var inflater: PlatformInflater? = null
    private var inflaterBuf = ByteArray(512)
    private var inflaterBufPos = 0
    private var inflaterBufLen = 0

    // Pushback buffer for bytes read from input but not consumed by inflater
    private var pushbackBuf: ByteArray? = null
    private var pushbackPos = 0
    private var pushbackLen = 0

    // For tracking data descriptor needs
    private var hasDataDescriptor = false

    // AES decryption state
    private var aesCipher: WinZipAesCipher? = null
    private var aesRemainingEncryptedBytes: Long = 0
    private var aesDecryptBuf = ByteArray(0) // buffer for decrypted data
    private var aesDecryptBufPos = 0
    private var aesDecryptBufLen = 0
    private var actualCompressionMethod: Int = -1

    // Legacy (ZipCrypto) decryption state
    private var legacyCipher: ZipCrypto? = null
    private var legacyRemainingEncryptedBytes: Long = 0

    fun readBytes(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var totalSize = 0
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n == -1) break
            chunks.add(buffer.copyOf(n))
            totalSize += n
        }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    val nextEntry: ZipEntry?
        get() {
            if (closed) throw Exception("Stream closed")
            closeEntry()

            return try {
                readNextEntry()
            } catch (_: Exception) {
                null
            }
        }

    private fun readNextEntry(): ZipEntry? {
        val sig = readLeInt()
        if (sig != ZipConstants.LOCAL_FILE_HEADER_SIGNATURE) return null

        @Suppress("UNUSED_VARIABLE")
        val versionNeeded = readLeShort()
        val generalFlag = readLeShort()
        val method = readLeShort()
        val lastModTime = readLeShort()
        val lastModDate = readLeShort()
        val crc32 = readLeInt().toLong() and 0xFFFFFFFFL
        val compressedSize = readLeInt().toLong() and 0xFFFFFFFFL
        val uncompressedSize = readLeInt().toLong() and 0xFFFFFFFFL
        val nameLen = readLeShort()
        val extraLen = readLeShort()

        hasDataDescriptor = (generalFlag and 0x08) != 0
        val isEncrypted = (generalFlag and 0x01) != 0

        val nameBytes = readExact(nameLen)
        val name = nameBytes.decodeToString()

        val extra = if (extraLen > 0) readExact(extraLen) else null

        val dosTime = (lastModDate.toLong() shl 16) or lastModTime.toLong()

        // Determine the actual compression method
        var effectiveMethod = method
        aesCipher = null
        legacyCipher = null
        actualCompressionMethod = method

        if (method == ZipConstants.AE_ENCRYPTED && isEncrypted) {
            val aesField = AesExtraField.parse(extra)
                ?: throw Exception("AES encrypted entry missing AES extra field")

            if (password == null) throw Exception("Password required for AES encrypted entry: $name")

            actualCompressionMethod = aesField.actualCompressionMethod
            effectiveMethod = aesField.actualCompressionMethod

            val salt = readExact(aesField.strength.saltLength)
            val pvv = readExact(2)

            val cipher = WinZipAesCipher(password, salt, aesField.strength)
            if (!constantTimeEquals(cipher.passwordVerification, pvv)) {
                throw Exception("Wrong password for entry: $name")
            }
            aesCipher = cipher

            // Encrypted data size = compressedSize - salt - pvv - authCode
            if (!hasDataDescriptor && compressedSize > 0) {
                aesRemainingEncryptedBytes = compressedSize -
                    aesField.strength.saltLength - 2 - WinZipAesCipher.AUTH_CODE_LENGTH
            } else {
                // For data descriptor entries, we'll rely on the inflater to detect end
                aesRemainingEncryptedBytes = Long.MAX_VALUE
            }
        } else if (isEncrypted) {
            // Traditional PKWare encryption (ZipCrypto)
            if (password == null) throw Exception("Password required for encrypted entry: $name")

            val cipher = ZipCrypto(password)

            // Read and decrypt the 12-byte encryption header
            val header = readExact(ZipCrypto.ENCRYPTION_HEADER_SIZE)
            cipher.decrypt(header, 0, ZipCrypto.ENCRYPTION_HEADER_SIZE)

            // Verify check byte (last byte of decrypted header)
            val checkByte = header[11].toInt() and 0xFF
            val crcCheck = ((crc32 ushr 24) and 0xFF).toInt()
            val timeCheck = ((dosTime ushr 8) and 0xFF).toInt()
            if (checkByte != crcCheck && checkByte != timeCheck) {
                throw Exception("Wrong password for entry: $name")
            }

            legacyCipher = cipher

            // Track remaining encrypted data bytes (compressedSize includes the 12-byte header).
            // Use compressedSize when available, even if data descriptor flag is set (some tools
            // like macOS zip fill in sizes AND set the data descriptor flag).
            legacyRemainingEncryptedBytes = if (compressedSize > 0) {
                compressedSize - ZipCrypto.ENCRYPTION_HEADER_SIZE
            } else {
                Long.MAX_VALUE
            }
        }

        val entry = ZipEntry(
            name = name,
            size = if (hasDataDescriptor) -1L else uncompressedSize,
            compressedSize = if (hasDataDescriptor) -1L else compressedSize,
            crc = if (hasDataDescriptor) -1L else crc32,
            method = effectiveMethod,
            time = dosTime,
            extra = extra,
        )

        currentEntry = entry
        entryEof = false

        when (effectiveMethod) {
            ZipConstants.STORED -> {
                remainingBytes = if (aesCipher != null) {
                    if (aesRemainingEncryptedBytes != Long.MAX_VALUE) {
                        aesRemainingEncryptedBytes
                    } else if (!hasDataDescriptor) {
                        uncompressedSize
                    } else {
                        Long.MAX_VALUE
                    }
                } else if (legacyCipher != null) {
                    // Compressed size includes 12-byte encryption header (already consumed)
                    if (!hasDataDescriptor && compressedSize > 0) {
                        compressedSize - ZipCrypto.ENCRYPTION_HEADER_SIZE
                    } else {
                        uncompressedSize
                    }
                } else {
                    if (hasDataDescriptor) Long.MAX_VALUE else compressedSize
                }
            }
            ZipConstants.DEFLATED -> {
                inflater = PlatformInflater().also { it.init() }
                inflaterBufPos = 0
                inflaterBufLen = 0
            }
            else -> throw Exception("Unsupported compression method: $effectiveMethod")
        }

        return entry
    }

    fun closeEntry() {
        if (entryEof) return
        val skipBuf = ByteArray(256)
        while (!entryEof) {
            if (read(skipBuf, 0, skipBuf.size) == -1) break
        }
        currentEntry = null
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val entry = currentEntry
        if (entryEof || entry == null) return -1
        return when (entry.method) {
            ZipConstants.STORED -> readStored(b, off, len)
            ZipConstants.DEFLATED -> readDeflated(b, off, len)
            else -> -1
        }
    }

    private fun readStored(b: ByteArray, off: Int, len: Int): Int {
        if (remainingBytes <= 0 && !hasDataDescriptor) {
            finishEntry()
            return -1
        }

        if (hasDataDescriptor && aesCipher == null && legacyCipher == null) {
            val n = readRaw(b, off, len)
            if (n == -1) {
                finishEntry()
                return -1
            }
            return n
        }

        val toRead = minOf(len.toLong(), remainingBytes).toInt()
        if (toRead <= 0) {
            finishEntry()
            return -1
        }

        val n = if (aesCipher != null) {
            readAndDecrypt(b, off, toRead)
        } else {
            readRaw(b, off, toRead)
        }
        if (n == -1) {
            finishEntry()
            return -1
        }
        legacyCipher?.decrypt(b, off, n)
        remainingBytes -= n
        if (remainingBytes <= 0) {
            finishEntry()
        }
        return n
    }

    private fun readDeflated(b: ByteArray, off: Int, len: Int): Int {
        val inf = inflater ?: return -1
        if (inf.isFinished) {
            finishEntry()
            return -1
        }

        while (true) {
            if (inflaterBufLen > inflaterBufPos) {
                val result = inf.inflate(
                    inflaterBuf, inflaterBufPos, inflaterBufLen - inflaterBufPos,
                    b, off, len
                )
                inflaterBufPos += result.bytesConsumed
                if (result.bytesProduced > 0) {
                    if (result.streamEnd) finishEntry()
                    return result.bytesProduced
                }
                if (result.streamEnd) {
                    finishEntry()
                    return -1
                }
            }

            // Fill the inflater buffer — decrypt if needed
            val n = if (aesCipher != null) {
                readAndDecryptToInflaterBuf()
            } else if (legacyCipher != null) {
                readAndDecryptLegacyToInflaterBuf()
            } else {
                readRaw(inflaterBuf, 0, inflaterBuf.size)
            }
            if (n == -1) {
                // No more input — try one final inflate to flush the inflater's internal state
                val result = inf.inflate(ByteArray(0), 0, 0, b, off, len)
                if (result.bytesProduced > 0) {
                    if (result.streamEnd) finishEntry()
                    return result.bytesProduced
                }
                finishEntry()
                return if (result.streamEnd) -1 else -1
            }
            inflaterBufPos = 0
            inflaterBufLen = n
        }
    }

    /** Read encrypted bytes from stream, decrypt, and place into inflater buffer. */
    private fun readAndDecryptToInflaterBuf(): Int {
        val cipher = aesCipher ?: return readRaw(inflaterBuf, 0, inflaterBuf.size)
        val toRead = if (aesRemainingEncryptedBytes != Long.MAX_VALUE) {
            minOf(inflaterBuf.size.toLong(), aesRemainingEncryptedBytes).toInt()
        } else {
            inflaterBuf.size
        }
        if (toRead <= 0) return -1

        val encBuf = ByteArray(toRead)
        val n = readRaw(encBuf, 0, toRead)
        if (n == -1) return -1

        cipher.decrypt(encBuf, 0, inflaterBuf, 0, n)
        if (aesRemainingEncryptedBytes != Long.MAX_VALUE) {
            aesRemainingEncryptedBytes -= n
        }
        return n
    }

    /** Read legacy-encrypted bytes, decrypt, and place into inflater buffer. */
    private fun readAndDecryptLegacyToInflaterBuf(): Int {
        val cipher = legacyCipher ?: return readRaw(inflaterBuf, 0, inflaterBuf.size)
        val toRead = if (legacyRemainingEncryptedBytes != Long.MAX_VALUE) {
            minOf(inflaterBuf.size.toLong(), legacyRemainingEncryptedBytes).toInt()
        } else {
            inflaterBuf.size
        }
        if (toRead <= 0) return -1

        val n = readRaw(inflaterBuf, 0, toRead)
        if (n == -1) return -1
        cipher.decrypt(inflaterBuf, 0, n)
        if (legacyRemainingEncryptedBytes != Long.MAX_VALUE) {
            legacyRemainingEncryptedBytes -= n
        }
        return n
    }

    /** Read and decrypt bytes directly into the output buffer. */
    private fun readAndDecrypt(b: ByteArray, off: Int, len: Int): Int {
        val cipher = aesCipher ?: return readRaw(b, off, len)
        val encBuf = ByteArray(len)
        val n = readRaw(encBuf, 0, len)
        if (n == -1) return -1
        cipher.decrypt(encBuf, 0, b, off, n)
        return n
    }

    private fun finishEntry() {
        if (entryEof) return
        entryEof = true

        // Save unconsumed inflater buffer bytes to pushback
        if (inflater != null && inflaterBufPos < inflaterBufLen) {
            // For encrypted entries, bytes are already decrypted — don't push back
            if (aesCipher == null && legacyCipher == null) {
                val remaining = inflaterBufLen - inflaterBufPos
                pushbackBuf = inflaterBuf.copyOfRange(inflaterBufPos, inflaterBufLen)
                pushbackPos = 0
                pushbackLen = remaining
            }
        }

        inflater?.end()
        inflater = null

        // Verify AES authentication code
        val aes = aesCipher
        if (aes != null) {
            try {
                val expectedAuthCode = readExact(WinZipAesCipher.AUTH_CODE_LENGTH)
                val actualAuthCode = aes.getAuthCode()
                if (!constantTimeEquals(actualAuthCode, expectedAuthCode)) {
                    throw Exception("AES authentication failed — data may be corrupted")
                }
            } finally {
                aesCipher = null
            }
        }

        legacyCipher = null

        if (hasDataDescriptor) {
            readDataDescriptor()
        }
    }

    private fun readDataDescriptor() {
        val possibleSig = readLeInt()
        if (possibleSig == ZipConstants.DATA_DESCRIPTOR_SIGNATURE) {
            val crc = readLeInt().toLong() and 0xFFFFFFFFL
            val compressedSize = readLeInt().toLong() and 0xFFFFFFFFL
            val size = readLeInt().toLong() and 0xFFFFFFFFL
            currentEntry?.let {
                it.crc = crc
                it.compressedSize = compressedSize
                it.size = size
            }
        } else {
            val crc = possibleSig.toLong() and 0xFFFFFFFFL
            val compressedSize = readLeInt().toLong() and 0xFFFFFFFFL
            val size = readLeInt().toLong() and 0xFFFFFFFFL
            currentEntry?.let {
                it.crc = crc
                it.compressedSize = compressedSize
                it.size = size
            }
        }
    }

    override fun available(): Int {
        return if (entryEof || currentEntry == null) 0 else 1
    }

    override fun close() {
        if (!closed) {
            closed = true
            inflater?.end()
            inflater = null
            input.close()
        }
    }

    // -- Low-level reading helpers that respect pushback buffer --

    private fun readRaw(b: ByteArray, off: Int, len: Int): Int {
        val pb = pushbackBuf
        if (pb != null && pushbackPos < pushbackLen) {
            val available = pushbackLen - pushbackPos
            val toRead = minOf(len, available)
            pb.copyInto(b, off, pushbackPos, pushbackPos + toRead)
            pushbackPos += toRead
            if (pushbackPos >= pushbackLen) {
                pushbackBuf = null
                pushbackPos = 0
                pushbackLen = 0
            }
            return toRead
        }
        return input.read(b, off, len)
    }

    private fun readRawByte(): Int {
        val pb = pushbackBuf
        if (pb != null && pushbackPos < pushbackLen) {
            val b = pb[pushbackPos++].toInt() and 0xFF
            if (pushbackPos >= pushbackLen) {
                pushbackBuf = null
                pushbackPos = 0
                pushbackLen = 0
            }
            return b
        }
        return input.read()
    }

    private fun readLeShort(): Int {
        val b0 = readByte()
        val b1 = readByte()
        return (b1 shl 8) or b0
    }

    private fun readLeInt(): Int {
        val b0 = readByte()
        val b1 = readByte()
        val b2 = readByte()
        val b3 = readByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun readByte(): Int {
        val b = readRawByte()
        if (b == -1) throw Exception("Unexpected end of ZIP stream")
        return b
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = readRaw(buf, offset, n - offset)
            if (read == -1) throw Exception("Unexpected end of ZIP stream")
            offset += read
        }
        return buf
    }

    companion object {
        /** Constant-time byte array comparison to prevent timing attacks. */
        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].toInt() xor b[i].toInt())
            }
            return result == 0
        }
    }
}

/** Convenience constructor for reading a ZIP from a byte array. */
fun ZipInputStream(data: ByteArray, password: ByteArray? = null): ZipInputStream {
    return ZipInputStream(no.synth.kmpzip.io.ByteArrayInputStream(data), password)
}

/** Convenience constructor with string password. */
fun ZipInputStream(input: InputStream, password: String): ZipInputStream {
    return ZipInputStream(input, password.encodeToByteArray())
}

/** Convenience constructor for reading a ZIP from a byte array with string password. */
fun ZipInputStream(data: ByteArray, password: String): ZipInputStream {
    return ZipInputStream(no.synth.kmpzip.io.ByteArrayInputStream(data), password.encodeToByteArray())
}
