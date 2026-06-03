package no.synth.kmpzip.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Guards the bulk-keystream optimization: the stateful [AesEcb] must produce exactly the
 * same bytes as encrypting each 16-byte block independently, and [WinZipAesCipher] must
 * round-trip regardless of how reads/writes are chunked relative to AES block boundaries.
 */
class AesEcbBulkTest {

    private fun keyOf(size: Int) = ByteArray(size) { (it * 7 + 1).toByte() }

    private fun deterministicBytes(n: Int) = ByteArray(n) { (it * 31 + 13).toByte() }

    // Encrypting N blocks in one call must equal N independent single-block encryptions.
    private fun assertBulkEqualsPerBlock(keySize: Int) {
        val blocks = 9 // 144 bytes: forces the multi-block loop, not a single block
        val plain = deterministicBytes(blocks * 16)

        val bulk = ByteArray(plain.size)
        AesEcb(keyOf(keySize)).apply { encryptBlocks(plain, bulk, blocks) }.also { it.clear() }

        val perBlock = ByteArray(plain.size)
        val single = AesEcb(keyOf(keySize))
        for (b in 0 until blocks) {
            val one = ByteArray(16)
            plain.copyInto(one, 0, b * 16, b * 16 + 16)
            single.encryptBlocks(one, one, 1)
            one.copyInto(perBlock, b * 16)
        }
        single.clear()

        assertContentEquals(perBlock, bulk, "AES-$keySize: bulk encryptBlocks must match per-block encryption")
    }

    @Test
    fun bulk_equals_per_block_aes128() = assertBulkEqualsPerBlock(16)

    @Test
    fun bulk_equals_per_block_aes192() = assertBulkEqualsPerBlock(24)

    @Test
    fun bulk_equals_per_block_aes256() = assertBulkEqualsPerBlock(32)

    // src == dst (in-place) must give the same ciphertext as separate buffers.
    @Test
    fun encrypt_in_place_matches_separate_buffers() {
        val plain = deterministicBytes(5 * 16)
        val separate = ByteArray(plain.size)
        AesEcb(keyOf(32)).apply { encryptBlocks(plain, separate, 5) }.clear()

        val inPlace = plain.copyOf()
        AesEcb(keyOf(32)).apply { encryptBlocks(inPlace, inPlace, 5) }.clear()

        assertContentEquals(separate, inPlace)
    }

    // WinZipAesCipher: decrypting in arbitrary chunk sizes (crossing block boundaries and
    // carrying partial blocks across crypt() calls) must reconstruct the original exactly,
    // and match a single-shot decrypt. Exercises keystreamPos/keystreamBuffer carryover.
    private fun assertChunkedRoundTrip(strength: AesStrength) {
        val password = "correct horse battery staple".encodeToByteArray()
        val salt = ByteArray(strength.saltLength) { (it + 1).toByte() }
        val plain = deterministicBytes(10_000) // many full blocks + a partial tail

        // Encrypt in one shot.
        val enc = ByteArray(plain.size)
        WinZipAesCipher(password, salt, strength).apply {
            encrypt(plain, 0, enc, 0, plain.size)
            getAuthCode()
        }

        // Single-shot decrypt as the reference.
        val refDec = ByteArray(enc.size)
        WinZipAesCipher(password, salt, strength).apply {
            decrypt(enc, 0, refDec, 0, enc.size)
            getAuthCode()
        }
        assertContentEquals(plain, refDec, "single-shot decrypt must recover the plaintext")

        // Chunked decrypt with sizes that straddle 16-byte boundaries.
        val chunks = intArrayOf(1, 7, 16, 15, 31, 17, 1, 2048, 333, 13)
        val chunkedDec = ByteArray(enc.size)
        val cipher = WinZipAesCipher(password, salt, strength)
        var off = 0
        var ci = 0
        while (off < enc.size) {
            val len = minOf(chunks[ci % chunks.size], enc.size - off)
            cipher.decrypt(enc, off, chunkedDec, off, len)
            off += len
            ci++
        }
        cipher.getAuthCode()
        assertContentEquals(refDec, chunkedDec, "chunked decrypt must equal single-shot decrypt")
        assertTrue(ci > 1, "test must actually span multiple crypt() calls")
    }

    @Test
    fun chunked_round_trip_aes128() = assertChunkedRoundTrip(AesStrength.AES_128)

    @Test
    fun chunked_round_trip_aes192() = assertChunkedRoundTrip(AesStrength.AES_192)

    @Test
    fun chunked_round_trip_aes256() = assertChunkedRoundTrip(AesStrength.AES_256)
}
