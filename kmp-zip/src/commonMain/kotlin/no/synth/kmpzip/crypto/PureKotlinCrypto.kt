package no.synth.kmpzip.crypto

// Pure-Kotlin AES-ECB / SHA-1 / HMAC-SHA1 / PBKDF2-HMAC-SHA1.
// Used as the actual implementation on Kotlin targets without a system crypto
// library: Linux native, Windows native, and wasmJs. JVM and Apple targets keep
// their faster platform impls.
//
// Security note: AES uses a table-based S-box (SBOX[...] with secret-dependent
// indices), which is not constant-time and leaks key bits via cache-timing on
// shared hardware. Acceptable for the original ZIP password-recovery threat
// model on Linux/Windows servers, but the residual risk grows on wasmJs in a
// browser tab next to attacker JavaScript — the README's "Threat model" section
// spells out the boundary. PBKDF2 and HMAC-SHA1 below are not table-based.
// Callers needing constant-time AES on these targets should bring their own.
// This impl zeroes intermediate key material on completion.

private const val SHA1_BLOCK_LEN = 64
private const val SHA1_DIGEST_LEN = 20
private const val HMAC_IPAD = 0x36
private const val HMAC_OPAD = 0x5c
private const val AES_BLOCK_LEN = 16

private val SBOX = intArrayOf(
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
)

private val RCON = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)

private fun xtime(b: Int): Int {
    val r = (b shl 1) and 0xff
    return if ((b and 0x80) != 0) r xor 0x1b else r
}

private fun subWord(w: Int): Int =
    (SBOX[(w ushr 24) and 0xff] shl 24) or
    (SBOX[(w ushr 16) and 0xff] shl 16) or
    (SBOX[(w ushr 8) and 0xff] shl 8) or
    SBOX[w and 0xff]

private fun keyExpansion(key: ByteArray): IntArray {
    val nk = key.size / 4
    val nr = nk + 6
    val totalWords = 4 * (nr + 1)
    val w = IntArray(totalWords)
    for (i in 0 until nk) {
        w[i] = ((key[4 * i].toInt() and 0xff) shl 24) or
               ((key[4 * i + 1].toInt() and 0xff) shl 16) or
               ((key[4 * i + 2].toInt() and 0xff) shl 8) or
               (key[4 * i + 3].toInt() and 0xff)
    }
    for (i in nk until totalWords) {
        var temp = w[i - 1]
        if (i % nk == 0) {
            val rotated = (temp shl 8) or (temp ushr 24)
            temp = subWord(rotated) xor (RCON[i / nk - 1] shl 24)
        } else if (nk > 6 && i % nk == 4) {
            temp = subWord(temp)
        }
        w[i] = w[i - nk] xor temp
    }
    return w
}

private fun addRoundKey(s: ByteArray, w: IntArray, round: Int) {
    for (c in 0 until 4) {
        val word = w[round * 4 + c]
        s[c * 4]     = (s[c * 4].toInt() xor ((word ushr 24) and 0xff)).toByte()
        s[c * 4 + 1] = (s[c * 4 + 1].toInt() xor ((word ushr 16) and 0xff)).toByte()
        s[c * 4 + 2] = (s[c * 4 + 2].toInt() xor ((word ushr 8) and 0xff)).toByte()
        s[c * 4 + 3] = (s[c * 4 + 3].toInt() xor (word and 0xff)).toByte()
    }
}

private fun subBytes(s: ByteArray) {
    for (i in 0 until AES_BLOCK_LEN) s[i] = SBOX[s[i].toInt() and 0xff].toByte()
}

private fun shiftRows(s: ByteArray) {
    val t1 = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t1
    val t2a = s[2]; val t2b = s[6]
    s[2] = s[10]; s[6] = s[14]; s[10] = t2a; s[14] = t2b
    val t3 = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t3
}

private fun mixColumns(s: ByteArray) {
    for (c in 0 until 4) {
        val s0 = s[c * 4].toInt() and 0xff
        val s1 = s[c * 4 + 1].toInt() and 0xff
        val s2 = s[c * 4 + 2].toInt() and 0xff
        val s3 = s[c * 4 + 3].toInt() and 0xff
        s[c * 4]     = (xtime(s0) xor (xtime(s1) xor s1) xor s2 xor s3).toByte()
        s[c * 4 + 1] = (s0 xor xtime(s1) xor (xtime(s2) xor s2) xor s3).toByte()
        s[c * 4 + 2] = (s0 xor s1 xor xtime(s2) xor (xtime(s3) xor s3)).toByte()
        s[c * 4 + 3] = ((xtime(s0) xor s0) xor s1 xor s2 xor xtime(s3)).toByte()
    }
}

internal fun aesEcbEncryptBlockImpl(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == AES_BLOCK_LEN) { "AES block must be $AES_BLOCK_LEN bytes" }
    require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key size: ${key.size}" }
    val w = keyExpansion(key)
    val nr = key.size / 4 + 6
    val s = block.copyOf()
    try {
        addRoundKey(s, w, 0)
        for (round in 1 until nr) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, w, round)
        }
        subBytes(s)
        shiftRows(s)
        addRoundKey(s, w, nr)
        return s
    } finally {
        // Round keys can be inverted to recover the AES key; clear before returning.
        w.fill(0)
    }
}

internal class Sha1Impl {
    private val h = intArrayOf(
        0x67452301, -0x10325477, -0x67452302, 0x10325476, -0x3c2d1e10
    )
    private val buffer = ByteArray(SHA1_BLOCK_LEN)
    private var bufferLen = 0
    private var totalBytes = 0L

    fun update(data: ByteArray, offset: Int, len: Int) {
        var off = offset
        var remaining = len
        totalBytes += len
        if (bufferLen > 0) {
            val toCopy = minOf(SHA1_BLOCK_LEN - bufferLen, remaining)
            data.copyInto(buffer, bufferLen, off, off + toCopy)
            bufferLen += toCopy
            off += toCopy
            remaining -= toCopy
            if (bufferLen == SHA1_BLOCK_LEN) {
                processBlock(buffer, 0)
                bufferLen = 0
            }
        }
        while (remaining >= SHA1_BLOCK_LEN) {
            processBlock(data, off)
            off += SHA1_BLOCK_LEN
            remaining -= SHA1_BLOCK_LEN
        }
        if (remaining > 0) {
            data.copyInto(buffer, 0, off, off + remaining)
            bufferLen = remaining
        }
    }

    fun doFinal(): ByteArray {
        val totalBits = totalBytes * 8
        buffer[bufferLen++] = 0x80.toByte()
        if (bufferLen > 56) {
            while (bufferLen < SHA1_BLOCK_LEN) buffer[bufferLen++] = 0
            processBlock(buffer, 0)
            bufferLen = 0
        }
        while (bufferLen < 56) buffer[bufferLen++] = 0
        for (i in 7 downTo 0) {
            buffer[bufferLen++] = ((totalBits ushr (i * 8)) and 0xff).toByte()
        }
        processBlock(buffer, 0)
        val out = ByteArray(SHA1_DIGEST_LEN)
        for (i in 0 until 5) {
            out[i * 4]     = ((h[i] ushr 24) and 0xff).toByte()
            out[i * 4 + 1] = ((h[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 2] = ((h[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 3] = (h[i] and 0xff).toByte()
        }
        // Clear running state so a leaked instance cannot be re-finalized.
        h.fill(0)
        buffer.fill(0)
        bufferLen = 0
        totalBytes = 0
        return out
    }

    private fun processBlock(data: ByteArray, offset: Int) {
        val w = IntArray(80)
        for (t in 0 until 16) {
            w[t] = ((data[offset + t * 4].toInt() and 0xff) shl 24) or
                   ((data[offset + t * 4 + 1].toInt() and 0xff) shl 16) or
                   ((data[offset + t * 4 + 2].toInt() and 0xff) shl 8) or
                   (data[offset + t * 4 + 3].toInt() and 0xff)
        }
        for (t in 16 until 80) {
            val v = w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]
            w[t] = (v shl 1) or (v ushr 31)
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
        for (t in 0 until 80) {
            val f: Int
            val k: Int
            when {
                t < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                t < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                t < 60 -> { f = (b and c) or (b and d) or (c and d); k = -0x70e44324 }
                else   -> { f = b xor c xor d; k = -0x359d3e2a }
            }
            val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[t]
            e = d
            d = c
            c = (b shl 30) or (b ushr 2)
            b = a
            a = temp
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
    }
}

internal class HmacSha1Impl(key: ByteArray) {
    private val opadKey: ByteArray
    private val sha = Sha1Impl()
    private var finalized = false

    init {
        val k = if (key.size > SHA1_BLOCK_LEN) {
            Sha1Impl().apply { update(key, 0, key.size) }.doFinal().copyOf(SHA1_BLOCK_LEN)
        } else {
            key.copyOf(SHA1_BLOCK_LEN)
        }
        val ipadKey = ByteArray(SHA1_BLOCK_LEN) { (k[it].toInt() xor HMAC_IPAD).toByte() }
        opadKey = ByteArray(SHA1_BLOCK_LEN) { (k[it].toInt() xor HMAC_OPAD).toByte() }
        sha.update(ipadKey, 0, SHA1_BLOCK_LEN)
        // Wipe the padded raw key and the inner pad — opadKey stays live until doFinal().
        k.fill(0)
        ipadKey.fill(0)
    }

    fun update(data: ByteArray, offset: Int, len: Int) {
        check(!finalized) { "HMAC already finalized" }
        sha.update(data, offset, len)
    }

    fun doFinal(): ByteArray {
        check(!finalized) { "HMAC already finalized" }
        finalized = true
        val inner = sha.doFinal()
        val outer = Sha1Impl()
        outer.update(opadKey, 0, SHA1_BLOCK_LEN)
        outer.update(inner, 0, SHA1_DIGEST_LEN)
        val mac = outer.doFinal()
        opadKey.fill(0)
        inner.fill(0)
        return mac
    }
}

internal fun pbkdf2HmacSha1Impl(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int,
): ByteArray {
    val numBlocks = (keyLengthBytes + SHA1_DIGEST_LEN - 1) / SHA1_DIGEST_LEN
    val derived = ByteArray(numBlocks * SHA1_DIGEST_LEN)
    for (i in 1..numBlocks) {
        val first = HmacSha1Impl(password)
        first.update(salt, 0, salt.size)
        val intBlock = byteArrayOf(
            ((i ushr 24) and 0xff).toByte(),
            ((i ushr 16) and 0xff).toByte(),
            ((i ushr 8) and 0xff).toByte(),
            (i and 0xff).toByte(),
        )
        first.update(intBlock, 0, 4)
        var u = first.doFinal()
        val t = u.copyOf()
        for (j in 1 until iterations) {
            val nextHmac = HmacSha1Impl(password)
            nextHmac.update(u, 0, u.size)
            val nextU = nextHmac.doFinal()
            u.fill(0)
            u = nextU
            for (k in 0 until SHA1_DIGEST_LEN) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        u.fill(0)
        t.copyInto(derived, (i - 1) * SHA1_DIGEST_LEN)
        t.fill(0)
    }
    val out = derived.copyOf(keyLengthBytes)
    derived.fill(0)
    return out
}
