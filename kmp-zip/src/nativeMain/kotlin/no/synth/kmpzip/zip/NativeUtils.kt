package no.synth.kmpzip.zip

import kotlinx.cinterop.*

// Safe cast: Byte and UByte have identical memory layout, but zlib expects UByteVar pointers.
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
internal fun toUBytePointer(pinned: Pinned<ByteArray>, offset: Int): CPointer<UByteVar> =
    pinned.addressOf(offset) as CPointer<UByteVar>
