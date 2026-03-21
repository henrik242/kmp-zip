package no.synth.kmpzip.crypto

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == 16) { "AES block must be 16 bytes" }
    val output = ByteArray(16)
    var dataOutMoved = 0.toULong()
    key.usePinned { keyPinned ->
        block.usePinned { blockPinned ->
            output.usePinned { outPinned ->
                memScoped {
                    val outMovedPtr = alloc<ULongVar>()
                    val status = CCCrypt(
                        kCCEncrypt,
                        kCCAlgorithmAES128,
                        kCCOptionECBMode,
                        keyPinned.addressOf(0),
                        key.size.toULong(),
                        null,
                        blockPinned.addressOf(0),
                        block.size.toULong(),
                        outPinned.addressOf(0),
                        output.size.toULong(),
                        outMovedPtr.ptr,
                    )
                    if (status != kCCSuccess) {
                        throw Exception("CCCrypt AES-ECB encrypt failed: $status")
                    }
                    dataOutMoved = outMovedPtr.value
                }
            }
        }
    }
    return output
}

@OptIn(ExperimentalForeignApi::class)
internal actual class HmacSha1Engine actual constructor(key: ByteArray) {
    private var context: CCHmacContext? = nativeHeap.alloc<CCHmacContext>()
    private var finalized = false

    init {
        val ctx = checkNotNull(context)
        key.usePinned { keyPinned ->
            CCHmacInit(ctx.ptr, kCCHmacAlgSHA1, keyPinned.addressOf(0), key.size.toULong())
        }
    }

    actual fun update(data: ByteArray, offset: Int, len: Int) {
        check(!finalized) { "HmacSha1Engine already finalized" }
        if (len == 0) return
        val ctx = checkNotNull(context)
        data.usePinned { pinned ->
            CCHmacUpdate(ctx.ptr, pinned.addressOf(offset), len.toULong())
        }
    }

    actual fun doFinal(): ByteArray {
        check(!finalized) { "HmacSha1Engine already finalized" }
        val ctx = checkNotNull(context)
        val result = ByteArray(CC_SHA1_DIGEST_LENGTH)
        result.usePinned { pinned ->
            CCHmacFinal(ctx.ptr, pinned.addressOf(0))
        }
        finalized = true
        nativeHeap.free(ctx)
        context = null
        return result
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun pbkdf2HmacSha1(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int,
): ByteArray {
    val derivedKey = ByteArray(keyLengthBytes)
    // CCKeyDerivationPBKDF's `password` param is `const char *` which Kotlin/Native
    // maps to `String?`. Decode UTF-8 bytes to String; the interop layer passes the
    // UTF-8 representation back to C, and passwordLen limits the bytes read.
    val passwordString = password.decodeToString()
    salt.usePinned { saltPinned ->
        derivedKey.usePinned { keyPinned ->
            val status = CCKeyDerivationPBKDF(
                kCCPBKDF2,
                passwordString,
                password.size.toULong(),
                saltPinned.addressOf(0).reinterpret<UByteVar>(),
                salt.size.toULong(),
                kCCPRFHmacAlgSHA1,
                iterations.toUInt(),
                keyPinned.addressOf(0).reinterpret<UByteVar>(),
                keyLengthBytes.toULong(),
            )
            if (status != kCCSuccess) {
                throw Exception("CCKeyDerivationPBKDF failed: $status")
            }
        }
    }
    return derivedKey
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
        if (status != 0) {
            throw Exception("SecRandomCopyBytes failed: $status")
        }
    }
    return bytes
}
