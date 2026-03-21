package no.synth.kmpzip.crypto

/**
 * Parses and writes the WinZip AES extra data field (header ID 0x9901).
 *
 * Structure (11 bytes total):
 * - 2 bytes: Header ID (0x9901)
 * - 2 bytes: Data size (7)
 * - 2 bytes: AE version (1 = AE-1, 2 = AE-2)
 * - 2 bytes: Vendor ID ("AE" = 0x4541)
 * - 1 byte:  AES strength (1=128, 2=192, 3=256)
 * - 2 bytes: Actual compression method
 */
internal data class AesExtraField(
    val version: Int,
    val strength: AesStrength,
    val actualCompressionMethod: Int,
) {
    companion object {
        const val HEADER_ID: Int = 0x9901
        const val DATA_SIZE: Int = 7
        const val TOTAL_SIZE: Int = 4 + DATA_SIZE // header ID + data size + data

        /**
         * Searches the extra field bytes for an AES extra data field.
         * Returns null if not found.
         */
        fun parse(extra: ByteArray?): AesExtraField? {
            if (extra == null || extra.size < TOTAL_SIZE) return null

            var offset = 0
            while (offset + 4 <= extra.size) {
                val headerId = readLeShort(extra, offset)
                val dataSize = readLeShort(extra, offset + 2)

                if (headerId == HEADER_ID && dataSize == DATA_SIZE && offset + 4 + dataSize <= extra.size) {
                    val dataOffset = offset + 4
                    val version = readLeShort(extra, dataOffset)
                    // Skip vendor ID (2 bytes at dataOffset + 2) — always "AE"
                    val strengthValue = extra[dataOffset + 4].toInt() and 0xFF
                    val actualMethod = readLeShort(extra, dataOffset + 5)

                    return AesExtraField(
                        version = version,
                        strength = AesStrength.fromExtraFieldValue(strengthValue),
                        actualCompressionMethod = actualMethod,
                    )
                }

                offset += 4 + dataSize
            }
            return null
        }

        /** Creates the AES extra field bytes to embed in a ZIP entry's extra data. */
        fun create(
            version: Int = 2,
            strength: AesStrength = AesStrength.AES_256,
            actualCompressionMethod: Int,
        ): ByteArray {
            val extra = ByteArray(TOTAL_SIZE)
            writeLeShort(extra, 0, HEADER_ID)
            writeLeShort(extra, 2, DATA_SIZE)
            writeLeShort(extra, 4, version)
            // Vendor ID "AE"
            extra[6] = 0x41.toByte() // 'A'
            extra[7] = 0x45.toByte() // 'E'
            extra[8] = strength.extraFieldValue.toByte()
            writeLeShort(extra, 9, actualCompressionMethod)
            return extra
        }

        private fun readLeShort(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun writeLeShort(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}
