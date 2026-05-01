package no.synth.kmpzip.crypto

// Shared crypto actuals for targets without a system crypto library:
// linuxX64, linuxArm64, mingwX64, wasmJs. They all delegate to the pure-Kotlin
// AES / HMAC-SHA1 / PBKDF2 in commonMain. JVM and Apple targets keep faster
// platform impls. `secureRandomBytes` stays per-target because the random
// source differs (/dev/urandom, BCryptGenRandom, Web Crypto).
internal actual fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray =
    aesEcbEncryptBlockImpl(key, block)

internal actual class HmacSha1Engine actual constructor(key: ByteArray) {
    private val impl = HmacSha1Impl(key)

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        impl.update(data, offset, len)
    }

    actual fun doFinal(): ByteArray = impl.doFinal()
}

internal actual fun pbkdf2HmacSha1(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int,
): ByteArray = pbkdf2HmacSha1Impl(password, salt, iterations, keyLengthBytes)
