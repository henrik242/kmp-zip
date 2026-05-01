@file:Suppress("UNUSED_PARAMETER")

package no.synth.kmpzip.crypto

import no.synth.kmpzip.internal.Uint8Array
import no.synth.kmpzip.zip.copyUint8ToByteArray

// AES / HMAC / PBKDF2 actuals live in pureKotlinCryptoMain (shared with
// linux/mingw native). Only the random-bytes source is wasmJs-specific.

// crypto.getRandomValues throws if the requested size > 65536 bytes. The
// public `Crypto.randomBytes(size)` API has no upper bound, so chunk.
private const val MAX_RANDOM_CHUNK = 65536

private fun cryptoGetRandomValues(buf: Uint8Array): Unit =
    js("globalThis.crypto.getRandomValues(buf)")

internal actual fun secureRandomBytes(size: Int): ByteArray {
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    var produced = 0
    while (produced < size) {
        val chunk = minOf(size - produced, MAX_RANDOM_CHUNK)
        val buf = Uint8Array(chunk)
        try {
            cryptoGetRandomValues(buf)
        } catch (e: Throwable) {
            // Translate the wasm trap from a missing or replaced Web Crypto
            // implementation into something a Kotlin caller can handle.
            throw IllegalStateException(
                "kmp-zip requires globalThis.crypto.getRandomValues, but this runtime " +
                    "doesn't expose it. Likely causes: Node <20, a sandboxed JS realm, " +
                    "or a browser with Web Crypto disabled by policy.",
                e,
            )
        }
        copyUint8ToByteArray(buf, 0, out, produced, chunk)
        produced += chunk
    }
    return out
}
