package no.synth.kmpzip.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.windows.BCRYPT_USE_SYSTEM_PREFERRED_RNG
import platform.windows.BCryptGenRandom

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        val status = BCryptGenRandom(
            null,
            pinned.addressOf(0).reinterpret<UByteVar>(),
            size.toUInt(),
            BCRYPT_USE_SYSTEM_PREFERRED_RNG.toUInt()
        )
        if (status != 0) {
            throw IllegalStateException("BCryptGenRandom failed: status=$status")
        }
    }
    return bytes
}
