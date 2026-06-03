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
    private val aesEcb: AesEcb
    private val hmac: HmacSha1Engine

    /** 2-byte password verification value derived from the password+salt. */
    val passwordVerification: ByteArray

    // AES-CTR state. The counter is a 128-bit little-endian value incremented before
    // each block (so the first keystream block uses counter == 1, per WinZip AES).
    private val counterBlock = ByteArray(AES_BLOCK_SIZE)
    // Leftover keystream from the last partial block, consumed before generating more.
    private val keystreamBuffer = ByteArray(AES_BLOCK_SIZE)
    private var keystreamPos = AES_BLOCK_SIZE // empty: force generation on first use

    // Reused scratch for bulk keystream generation, grown to the largest crypt() request.
    // Counter blocks are built here, ECB-encrypted in one call, then XORed into the output.
    private var counterScratch = ByteArray(0)
    private var keystreamScratch = ByteArray(0)

    init {
        val keyLen = strength.keyBytes
        val derivedKeyLen = 2 * keyLen + 2
        val derivedKey = pbkdf2HmacSha1(password, salt, PBKDF2_ITERATIONS, derivedKeyLen)

        aesKey = derivedKey.copyOfRange(0, keyLen)
        aesEcb = AesEcb(aesKey)
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
        // Zero sensitive state, including the retained AES key schedule inside aesEcb
        // (its expanded round keys are invertible to the AES key).
        aesKey.fill(0)
        aesEcb.clear()
        keystreamBuffer.fill(0)
        keystreamScratch.fill(0)
        counterScratch.fill(0)
        counterBlock.fill(0)
        return authCode
    }

    private fun crypt(
        input: ByteArray, inputOffset: Int,
        output: ByteArray, outputOffset: Int,
        len: Int,
    ) {
        var pos = 0

        // 1. Drain any leftover keystream from the previous call's trailing partial block.
        while (keystreamPos < AES_BLOCK_SIZE && pos < len) {
            output[outputOffset + pos] =
                (input[inputOffset + pos].toInt() xor keystreamBuffer[keystreamPos].toInt()).toByte()
            keystreamPos++
            pos++
        }

        // 2. Bulk-encrypt all whole blocks in one AES call instead of one per 16 bytes.
        val fullBlocks = (len - pos) / AES_BLOCK_SIZE
        if (fullBlocks > 0) {
            val needed = fullBlocks * AES_BLOCK_SIZE
            if (counterScratch.size < needed) {
                counterScratch = ByteArray(needed)
                keystreamScratch = ByteArray(needed)
            }
            for (b in 0 until fullBlocks) {
                incrementCounter()
                counterBlock.copyInto(counterScratch, b * AES_BLOCK_SIZE)
            }
            aesEcb.encryptBlocks(counterScratch, keystreamScratch, fullBlocks)
            for (i in 0 until needed) {
                output[outputOffset + pos + i] =
                    (input[inputOffset + pos + i].toInt() xor keystreamScratch[i].toInt()).toByte()
            }
            pos += needed
        }

        // 3. Trailing partial block: generate one keystream block and keep the remainder
        //    in keystreamBuffer for the next call.
        if (pos < len) {
            incrementCounter()
            aesEcb.encryptBlocks(counterBlock, keystreamBuffer, 1)
            keystreamPos = 0
            while (pos < len) {
                output[outputOffset + pos] =
                    (input[inputOffset + pos].toInt() xor keystreamBuffer[keystreamPos].toInt()).toByte()
                keystreamPos++
                pos++
            }
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
