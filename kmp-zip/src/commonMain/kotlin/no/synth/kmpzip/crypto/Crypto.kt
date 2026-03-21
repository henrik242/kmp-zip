package no.synth.kmpzip.crypto

/**
 * Cross-platform cryptographic primitives.
 *
 * Uses platform-native implementations:
 * - JVM: javax.crypto (PBKDF2WithHmacSHA1, HmacSHA1, AES)
 * - iOS/Native: Apple CommonCrypto (CCKeyDerivationPBKDF, CCHmac, CCCrypt)
 */
object Crypto {

    /**
     * Derives a key from a password and salt using PBKDF2 with HMAC-SHA1.
     *
     * @param password the password bytes (typically UTF-8 encoded)
     * @param salt the salt bytes
     * @param iterations the number of PBKDF2 iterations (minimum 1000 recommended)
     * @param keyLengthBytes the desired derived key length in bytes
     * @return the derived key
     */
    fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray = pbkdf2HmacSha1(password, salt, iterations, keyLengthBytes)

    /**
     * Computes HMAC-SHA1 over the given data.
     *
     * @param key the HMAC key
     * @param data the data to authenticate
     * @return the 20-byte HMAC-SHA1 result
     */
    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val engine = HmacSha1Engine(key)
        engine.update(data, 0, data.size)
        return engine.doFinal()
    }

    /**
     * Generates cryptographically secure random bytes.
     *
     * Uses platform-native secure random:
     * - JVM: java.security.SecureRandom
     * - iOS/Native: Security.SecRandomCopyBytes
     *
     * @param size the number of random bytes to generate
     * @return the random bytes
     */
    fun randomBytes(size: Int): ByteArray = secureRandomBytes(size)

    /**
     * Computes CRC-32 checksum using polynomial 0xEDB88320 (ISO 3309 / ITU-T V.42).
     *
     * This is the standard CRC-32 used in ZIP, GZIP, PNG, and many other formats.
     * Implemented in pure Kotlin with no platform dependencies.
     *
     * @param data the input bytes
     * @return the CRC-32 checksum
     */
    fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = (crc ushr 8) xor CRC32_TABLE[(crc xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
    }

    // Shared CRC32 lookup table (polynomial 0xEDB88320)
    internal val CRC32_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) {
                0xEDB88320.toInt() xor (c ushr 1)
            } else {
                c ushr 1
            }
        }
        c
    }
}
