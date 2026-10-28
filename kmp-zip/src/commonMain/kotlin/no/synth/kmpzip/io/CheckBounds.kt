package no.synth.kmpzip.io

/** Rejects a slice outside [b], as java.io streams do. */
internal fun checkBounds(b: ByteArray, off: Int, len: Int) {
    if (off < 0 || len < 0 || len > b.size - off) {
        throw IndexOutOfBoundsException("off=$off, len=$len, size=${b.size}")
    }
}
