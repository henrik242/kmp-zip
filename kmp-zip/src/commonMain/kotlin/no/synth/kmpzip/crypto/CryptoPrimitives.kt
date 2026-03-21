package no.synth.kmpzip.crypto

/** Encrypts a single 16-byte block using AES-ECB with the given key. */
internal expect fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray

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
