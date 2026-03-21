package no.synth.kmpzip.crypto

/**
 * PKWare traditional ZIP encryption (ZipCrypto).
 *
 * A stream cipher using three 32-bit keys updated via CRC32 and a
 * linear congruential generator. Provides basic password protection
 * compatible with all ZIP tools, but is cryptographically weak
 * (vulnerable to known-plaintext attacks).
 *
 * For strong encryption, use AES via [WinZipAesCipher] instead.
 */
internal class ZipCrypto(password: ByteArray) {
    private var key0: Int = 0x12345678
    private var key1: Int = 0x23456789
    private var key2: Int = 0x34567890

    init {
        for (b in password) {
            updateKeys(b.toInt() and 0xFF)
        }
    }

    /** Decrypt a single byte and update keys with the plaintext. */
    fun decryptByte(cipherByte: Int): Int {
        val keystream = keystreamByte()
        val plain = (cipherByte xor keystream) and 0xFF
        updateKeys(plain)
        return plain
    }

    /** Encrypt a single byte and update keys with the plaintext. */
    fun encryptByte(plainByte: Int): Int {
        val keystream = keystreamByte()
        val cipher = (plainByte xor keystream) and 0xFF
        updateKeys(plainByte and 0xFF)
        return cipher
    }

    /** Decrypt a buffer region in place. */
    fun decrypt(buf: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= buf.size) {
            "Invalid range: off=$off, len=$len, buf.size=${buf.size}"
        }
        for (i in off until off + len) {
            buf[i] = decryptByte(buf[i].toInt() and 0xFF).toByte()
        }
    }

    /** Encrypt a buffer region in place. */
    fun encrypt(buf: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= buf.size) {
            "Invalid range: off=$off, len=$len, buf.size=${buf.size}"
        }
        for (i in off until off + len) {
            buf[i] = encryptByte(buf[i].toInt() and 0xFF).toByte()
        }
    }

    private fun keystreamByte(): Int {
        val temp = (key2 or 3) and 0xFFFF
        return ((temp * (temp xor 1)) ushr 8) and 0xFF
    }

    private fun updateKeys(plainByte: Int) {
        key0 = crc32Update(key0, plainByte)
        key1 = (key1 + (key0 and 0xFF))
        key1 = key1 * 0x08088405 + 1
        key2 = crc32Update(key2, (key1 ushr 24) and 0xFF)
    }

    companion object {
        const val ENCRYPTION_HEADER_SIZE = 12

        /** Generate a 12-byte encryption header for writing. */
        fun createEncryptionHeader(
            cipher: ZipCrypto,
            crcCheckByte: Int,
        ): ByteArray {
            val header = ByteArray(ENCRYPTION_HEADER_SIZE)
            // Fill first 11 bytes with random data
            val random = secureRandomBytes(11)
            random.copyInto(header, 0)
            // Last byte is the CRC check byte
            header[11] = crcCheckByte.toByte()
            // Encrypt the entire header
            cipher.encrypt(header, 0, ENCRYPTION_HEADER_SIZE)
            return header
        }

        private fun crc32Update(crc: Int, byte: Int): Int =
            (crc ushr 8) xor Crypto.CRC32_TABLE[(crc xor byte) and 0xFF]
    }
}
