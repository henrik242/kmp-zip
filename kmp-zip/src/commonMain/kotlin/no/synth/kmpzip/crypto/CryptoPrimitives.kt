package no.synth.kmpzip.crypto

/**
 * AES-ECB block encryptor that expands the key schedule once at construction and
 * reuses it for every call. This is the building block for the AES-CTR keystream in
 * [WinZipAesCipher]; encrypting many counter blocks per call amortizes the key
 * schedule (and, on JVM/Apple, the platform cipher setup and call overhead) that
 * would otherwise be paid per 16-byte block — the difference between ~0.5 MB/s and
 * tens of MB/s on large encrypted entries.
 */
internal expect class AesEcb(key: ByteArray) {
    /**
     * ECB-encrypts [blockCount] consecutive 16-byte blocks read from [src] (offset 0),
     * writing the ciphertext to [dst] (offset 0). [src] and [dst] may be the same array.
     * Both must hold at least `blockCount * 16` bytes.
     */
    fun encryptBlocks(src: ByteArray, dst: ByteArray, blockCount: Int)

    /**
     * Best-effort zeroing of the retained key schedule / key material. The expanded
     * round keys can be inverted to recover the AES key, so callers clear() once the
     * cipher is done (the instance must not be used afterward). The JVM provider does
     * not expose its internal schedule, so that path is best-effort.
     */
    fun clear()
}

/** Streaming HMAC-SHA1 engine. */
internal expect class HmacSha1Engine(key: ByteArray) {
    fun update(data: ByteArray, offset: Int, len: Int)
    fun doFinal(): ByteArray
}

/** PBKDF2 with HMAC-SHA1. Returns derived key of the specified length in bytes. */
internal expect fun pbkdf2HmacSha1(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int,
): ByteArray

/** Generates cryptographically secure random bytes. */
internal expect fun secureRandomBytes(size: Int): ByteArray
