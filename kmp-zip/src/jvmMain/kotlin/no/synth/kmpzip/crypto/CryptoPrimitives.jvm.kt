package no.synth.kmpzip.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// AES-ECB here is the single-block primitive used to build the AES-CTR keystream
// in WinZipAesCipher (WinZip AE-2 / ZIP AES). Authentication is provided separately
// by HMAC-SHA1 (encrypt-then-MAC). The ZIP AES format is fixed; GCM is not an option.
private val cipherThreadLocal = ThreadLocal.withInitial {
    Cipher.getInstance("AES/ECB/NoPadding") // lgtm[java/weak-cryptographic-algorithm]
}

private val secureRandom = java.security.SecureRandom()

internal actual fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == 16) { "AES block must be 16 bytes" }
    require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key size: ${key.size}" }
    val cipher = cipherThreadLocal.get()
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(block)
}

internal actual class HmacSha1Engine actual constructor(key: ByteArray) {
    private val mac: Mac = Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(key, "HmacSHA1"))
    }

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        mac.update(data, offset, len)
    }

    actual fun doFinal(): ByteArray = mac.doFinal()
}

internal actual fun pbkdf2HmacSha1(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int,
): ByteArray {
    // PBEKeySpec takes char[] which the JVM internally re-encodes as UTF-8 bytes for PBKDF2.
    // We decode the raw password bytes as UTF-8 first so the round-trip (bytes→chars→UTF-8 bytes)
    // produces the original byte sequence, matching the native impl which uses raw bytes directly.
    val chars = password.decodeToString().toCharArray()
    val spec = PBEKeySpec(chars, salt, iterations, keyLengthBytes * 8)
    chars.fill('\u0000')
    try {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}
