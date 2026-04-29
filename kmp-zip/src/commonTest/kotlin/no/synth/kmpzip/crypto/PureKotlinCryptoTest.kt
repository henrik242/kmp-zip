package no.synth.kmpzip.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals

private fun hex(s: String): ByteArray {
    val clean = s.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { i ->
        ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
    }
}

class PureKotlinCryptoTest {

    // FIPS-197 Appendix C.1: AES-128
    @Test
    fun aesEcbEncryptBlock_aes128_fips() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex("00112233445566778899aabbccddeeff")
        val expected = hex("69c4e0d86a7b0430d8cdb78070b4c55a")
        assertContentEquals(expected, aesEcbEncryptBlockImpl(key, plaintext))
    }

    // FIPS-197 Appendix C.2: AES-192
    @Test
    fun aesEcbEncryptBlock_aes192_fips() {
        val key = hex("000102030405060708090a0b0c0d0e0f1011121314151617")
        val plaintext = hex("00112233445566778899aabbccddeeff")
        val expected = hex("dda97ca4864cdfe06eaf70a0ec0d7191")
        assertContentEquals(expected, aesEcbEncryptBlockImpl(key, plaintext))
    }

    // FIPS-197 Appendix C.3: AES-256
    @Test
    fun aesEcbEncryptBlock_aes256_fips() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val plaintext = hex("00112233445566778899aabbccddeeff")
        val expected = hex("8ea2b7ca516745bfeafc49904b496089")
        assertContentEquals(expected, aesEcbEncryptBlockImpl(key, plaintext))
    }

    // RFC 2202 §3 test case 1: HMAC-SHA1, 20-byte 0x0b key, "Hi There"
    @Test
    fun hmacSha1_rfc2202_case1() {
        val key = ByteArray(20) { 0x0b.toByte() }
        val data = "Hi There".encodeToByteArray()
        val expected = hex("b617318655057264e28bc0b6fb378c8ef146be00")
        val mac = HmacSha1Impl(key).apply { update(data, 0, data.size) }.doFinal()
        assertContentEquals(expected, mac)
    }

    // RFC 2202 §3 test case 2: key "Jefe", data "what do ya want for nothing?"
    @Test
    fun hmacSha1_rfc2202_case2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expected = hex("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79")
        val mac = HmacSha1Impl(key).apply { update(data, 0, data.size) }.doFinal()
        assertContentEquals(expected, mac)
    }

    // RFC 2202 §3 test case 5: key 20 bytes 0x0c, "Test With Truncation"
    @Test
    fun hmacSha1_rfc2202_case5() {
        val key = ByteArray(20) { 0x0c.toByte() }
        val data = "Test With Truncation".encodeToByteArray()
        val expected = hex("4c1a03424b55e07fe7f27be1d58bb9324a9a5a04")
        val mac = HmacSha1Impl(key).apply { update(data, 0, data.size) }.doFinal()
        assertContentEquals(expected, mac)
    }

    // RFC 2202 §3 test case 6: 80-byte 0xaa key (longer than block size — exercises key hashing)
    @Test
    fun hmacSha1_rfc2202_case6() {
        val key = ByteArray(80) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        val expected = hex("aa4ae5e15272d00e95705637ce8a3b55ed402112")
        val mac = HmacSha1Impl(key).apply { update(data, 0, data.size) }.doFinal()
        assertContentEquals(expected, mac)
    }

    // RFC 6070 §2: PBKDF2-HMAC-SHA1, password "password", salt "salt", 1 iteration, dkLen=20
    @Test
    fun pbkdf2HmacSha1_rfc6070_case1() {
        val expected = hex("0c60c80f961f0e71f3a9b524af6012062fe037a6")
        val derived = pbkdf2HmacSha1Impl(
            "password".encodeToByteArray(),
            "salt".encodeToByteArray(),
            iterations = 1,
            keyLengthBytes = 20,
        )
        assertContentEquals(expected, derived)
    }

    // RFC 6070 §2: 4096 iterations
    @Test
    fun pbkdf2HmacSha1_rfc6070_case3() {
        val expected = hex("4b007901b765489abead49d926f721d065a429c1")
        val derived = pbkdf2HmacSha1Impl(
            "password".encodeToByteArray(),
            "salt".encodeToByteArray(),
            iterations = 4096,
            keyLengthBytes = 20,
        )
        assertContentEquals(expected, derived)
    }

    // RFC 6070 §2: longer password/salt, 25-byte derived key (exercises multi-block output)
    @Test
    fun pbkdf2HmacSha1_rfc6070_case5() {
        val expected = hex("3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038")
        val derived = pbkdf2HmacSha1Impl(
            "passwordPASSWORDpassword".encodeToByteArray(),
            "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray(),
            iterations = 4096,
            keyLengthBytes = 25,
        )
        assertContentEquals(expected, derived)
    }

    // SHA-1 of "abc" — sanity check for the streaming hash itself
    @Test
    fun sha1_streaming_abc() {
        val expected = hex("a9993e364706816aba3e25717850c26c9cd0d89d")
        val sha = Sha1Impl()
        sha.update("abc".encodeToByteArray(), 0, 3)
        assertContentEquals(expected, sha.doFinal())
    }

    // SHA-1 of empty input
    @Test
    fun sha1_streaming_empty() {
        val expected = hex("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        assertContentEquals(expected, Sha1Impl().doFinal())
    }

    // FIPS 180-4: 56-byte message — boundary case for padding (needs an extra block).
    @Test
    fun sha1_streaming_56bytes() {
        val msg = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        val expected = hex("84983e441c3bd26ebaae4aa1f95129e5e54670f1")
        val sha = Sha1Impl()
        sha.update(msg, 0, msg.size)
        assertContentEquals(expected, sha.doFinal())
    }
}
