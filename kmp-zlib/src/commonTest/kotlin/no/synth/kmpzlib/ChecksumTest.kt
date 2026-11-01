package no.synth.kmpzlib

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ChecksumTest {
    private fun crcBitwise(data: ByteArray): Int {
        var c = -1
        for (b in data) {
            c = c xor (b.toInt() and 0xff)
            repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        }
        return c.inv()
    }

    private fun adlerSlow(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (x in data) {
            a = (a + (x.toInt() and 0xff)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    @Test
    fun knownValues() {
        val check = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926.toInt(), crc32(0, check, check.size, 0))
        assertEquals(0x091E01DE, adler32(1, check, check.size, 0))
        val wiki = "Wikipedia".encodeToByteArray()
        assertEquals(0x11E60398, adler32(1, wiki, wiki.size, 0))
        assertEquals(0, crc32(0, ByteArray(0), 0, 0))
        assertEquals(1, adler32(1, ByteArray(0), 0, 0))
    }

    @Test
    fun matchesReferenceForAllLengthsAndOffsets() {
        val data = Random(3).nextBytes(300)
        for (off in 0 until 9) {
            for (len in 0..data.size - off) {
                val slice = data.copyOfRange(off, off + len)
                assertEquals(crcBitwise(slice), crc32(0, data, len, off), "crc off $off len $len")
                assertEquals(adlerSlow(slice), adler32(1, data, len, off), "adler off $off len $len")
            }
        }
    }

    @Test
    fun incrementalUpdatesMatchOneShot() {
        val data = Random(5).nextBytes(20_000)
        val crc = crc32(0, data, data.size, 0)
        val adler = adler32(1, data, data.size, 0)
        for (split in listOf(1, 3, 7, 8, 9, 3799, 3800, 3801, 7777)) {
            var c = 0
            var a = 1
            var pos = 0
            while (pos < data.size) {
                val n = minOf(split, data.size - pos)
                c = crc32(c, data, n, pos)
                a = adler32(a, data, n, pos)
                pos += n
            }
            assertEquals(crc, c, "crc split $split")
            assertEquals(adler, a, "adler split $split")
        }
    }

    @Test
    fun adlerWorstCaseSumsDoNotOverflow() {
        // All 0xff maximizes the sums between modulo reductions.
        for (n in listOf(3799, 3800, 3801, 3854, 3855, 7600, 100_000)) {
            val data = ByteArray(n) { -1 }
            assertEquals(adlerSlow(data), adler32(1, data, n, 0), "n $n")
            // Start from the largest possible running sums too.
            val start = (65520 shl 16) or 65520
            var a = 65520
            var b = 65520
            for (i in 0 until n) {
                a = (a + 255) % 65521
                b = (b + a) % 65521
            }
            assertEquals((b shl 16) or a, adler32(start, data, n, 0), "n $n from max")
        }
    }

    @Test
    fun publicCrc32MatchesIncrementalAndReference() {
        val data = Random(3).nextBytes(10_000)
        val whole = Crc32().apply { update(data) }
        val parts = Crc32()
        var off = 0
        for (n in listOf(1, 2, 3, 4, 5, 1000, 8985)) {
            parts.update(data, off, n)
            off += n
        }
        val expected = crcBitwise(data).toLong() and 0xFFFFFFFFL
        assertEquals(expected, whole.value)
        assertEquals(expected, parts.value)
        whole.reset()
        assertEquals(0L, whole.value)
        whole.update("123456789".encodeToByteArray())
        assertEquals(0xCBF43926L, whole.value)
    }
}
