package no.synth.kmpzip.zip

import kotlinx.cinterop.*

// Safe cast: Byte and UByte have identical memory layout, but zlib expects UByteVar pointers.
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
internal fun toUBytePointer(pinned: Pinned<ByteArray>, offset: Int): CPointer<UByteVar> =
    pinned.addressOf(offset) as CPointer<UByteVar>

/**
 * Pin [arr] only when [len] is positive and invoke [block] with a UByte* into the
 * region — or with `null` when [len] is zero. Solves two zlib-cinterop traps:
 *  - `Pinned<ByteArray>.addressOf(arr.size)` throws ArrayIndexOutOfBoundsException
 *    even though zlib treats one-past-the-end as legal when `avail_* == 0`.
 *  - Pinning empty arrays is wasteful and conceptually meaningless.
 *
 * zlib ignores `next_in`/`next_out` when the corresponding `avail_*` is zero, so a
 * null pointer is safe to pass.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <R> withPinned(
    arr: ByteArray,
    offset: Int,
    len: Int,
    block: (CPointer<UByteVar>?) -> R,
): R {
    val pin = if (len > 0) arr.pin() else null
    return try {
        block(if (pin != null) toUBytePointer(pin, offset) else null)
    } finally {
        pin?.unpin()
    }
}
