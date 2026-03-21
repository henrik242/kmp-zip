package no.synth.kmpzip.crypto

/**
 * WinZip AES encryption/decryption cipher.
 *
 * Implements the WinZip AES encryption scheme (AE-1/AE-2) using:
 * - PBKDF2-HMAC-SHA1 for key derivation (1000 iterations)
 * - AES in CTR mode with little-endian counter starting at 1
 * - HMAC-SHA1 truncated to 10 bytes for authentication
 */
internal class WinZipAesCipher(
    password: ByteArray,
    salt: ByteArray,
    strength: AesStrength,
) {
    private val aesKey: ByteArray
    private val hmac: HmacSha1Engine

    /** 2-byte password verification value derived from the password+salt. */
    val passwordVerification: ByteArray

    // AES-CTR state
    private val counterBlock = ByteArray(AES_BLOCK_SIZE)
    private var keystreamBuffer = ByteArray(AES_BLOCK_SIZE)
    private var keystreamPos = AES_BLOCK_SIZE // force new keystream generation on first use

    init {
        val keyLen = strength.keyBytes
        val derivedKeyLen = 2 * keyLen + 2
        val derivedKey = pbkdf2HmacSha1(password, salt, PBKDF2_ITERATIONS, derivedKeyLen)

        aesKey = derivedKey.copyOfRange(0, keyLen)
        val hmacKey = derivedKey.copyOfRange(keyLen, 2 * keyLen)
        passwordVerification = derivedKey.copyOfRange(2 * keyLen, 2 * keyLen + 2)

        hmac = HmacSha1Engine(hmacKey)

        // Zero intermediate key material
        derivedKey.fill(0)
        hmacKey.fill(0)
    }

    /**
     * Encrypts data in-place style: reads from [input] and writes to [output].
     * HMAC is accumulated over the encrypted (output) data.
     */
    fun encrypt(
        input: ByteArray, inputOffset: Int,
        output: ByteArray, outputOffset: Int,
        len: Int,
    ) {
        crypt(input, inputOffset, output, outputOffset, len)
        hmac.update(output, outputOffset, len)
    }

    /**
     * Decrypts data: reads from [input] and writes to [output].
     * HMAC is accumulated over the encrypted (input) data before decryption.
     */
    fun decrypt(
        input: ByteArray, inputOffset: Int,
        output: ByteArray, outputOffset: Int,
        len: Int,
    ) {
        hmac.update(input, inputOffset, len)
        crypt(input, inputOffset, output, outputOffset, len)
    }

    /**
     * Returns the 10-byte authentication code (truncated HMAC-SHA1).
     * Call this after all data has been encrypted/decrypted.
     * Also zeroes sensitive key material.
     */
    fun getAuthCode(): ByteArray {
        val authCode = hmac.doFinal().copyOfRange(0, AUTH_CODE_LENGTH)
        // Zero sensitive state
        aesKey.fill(0)
        keystreamBuffer.fill(0)
        counterBlock.fill(0)
        return authCode
    }

    private fun crypt(
        input: ByteArray, inputOffset: Int,
        output: ByteArray, outputOffset: Int,
        len: Int,
    ) {
        for (i in 0 until len) {
            if (keystreamPos >= AES_BLOCK_SIZE) {
                incrementCounter()
                keystreamBuffer = aesEcbEncryptBlock(aesKey, counterBlock)
                keystreamPos = 0
            }
            output[outputOffset + i] =
                (input[inputOffset + i].toInt() xor keystreamBuffer[keystreamPos].toInt()).toByte()
            keystreamPos++
        }
    }

    /** Increment the 128-bit little-endian counter. */
    private fun incrementCounter() {
        for (i in counterBlock.indices) {
            val newVal = (counterBlock[i].toInt() and 0xFF) + 1
            counterBlock[i] = newVal.toByte()
            if (newVal <= 0xFF) break
        }
    }

    companion object {
        const val AES_BLOCK_SIZE = 16
        const val PBKDF2_ITERATIONS = 1000
        const val AUTH_CODE_LENGTH = 10
    }
}
