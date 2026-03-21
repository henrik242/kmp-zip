package no.synth.kmpzip.crypto

/** WinZip AES encryption key strength. */
enum class AesStrength(
    /** AES key size in bytes. */
    val keyBytes: Int,
    /** Salt length in bytes (half the key size). */
    val saltLength: Int,
    /** Value stored in the AES extra field. */
    internal val extraFieldValue: Int,
) {
    AES_128(16, 8, 1),
    AES_192(24, 12, 2),
    AES_256(32, 16, 3);

    companion object {
        internal fun fromExtraFieldValue(value: Int): AesStrength =
            entries.firstOrNull { it.extraFieldValue == value }
                ?: throw IllegalArgumentException("Unknown AES strength value: $value")
    }
}
