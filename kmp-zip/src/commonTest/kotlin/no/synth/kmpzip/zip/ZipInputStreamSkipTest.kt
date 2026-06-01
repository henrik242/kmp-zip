package no.synth.kmpzip.zip

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises closeEntry when advancing past an entry without reading it: the stream must
 * land exactly on the next local header so later entries still decode correctly.
 *
 * Two paths exist. The fast path (skip raw bytes, no decode) requires an untouched entry
 * with a known size and no data descriptor — exercised by the AES and cli-mixed fixtures.
 * Archives written with streaming data descriptors (multi-entry and legacy-multi here) and
 * partially-read entries take the decode-drain fallback. The `*UsesSkip`/`*UsesDecode`
 * tests assert via a counting stream which path actually runs.
 */
class ZipInputStreamSkipTest {

    private fun open(data: ByteArray, password: String?) =
        if (password != null) ZipInputStream(data, password) else ZipInputStream(data)

    /** Baseline: every entry read normally, in order. */
    private fun readAll(data: ByteArray, password: String?): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        open(data, password).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                out.add(e.name to zis.readBytes())
            }
        }
        return out
    }

    /** Advances past the first [index] entries WITHOUT reading them, then reads entry [index]. */
    private fun skipToAndRead(data: ByteArray, password: String?, index: Int): Pair<String, ByteArray> {
        open(data, password).use { zis ->
            var entry: ZipEntry? = null
            repeat(index + 1) { entry = zis.nextEntry }
            return entry!!.name to zis.readBytes()
        }
    }

    private fun assertSkipPositionsCorrectly(name: String, data: ByteArray, password: String?) {
        val baseline = readAll(data, password)
        assertTrue(baseline.size >= 2, "$name needs >= 2 entries to test skipping")
        for (i in baseline.indices) {
            val (skipName, skipBytes) = skipToAndRead(data, password, i)
            assertEquals(baseline[i].first, skipName, "$name entry $i name after skipping")
            assertContentEquals(baseline[i].second, skipBytes, "$name entry $i content after skipping")
        }
    }

    @Test
    fun skipPositionsCorrectlyPlain() =
        assertSkipPositionsCorrectly("multiEntry", TestData.multiEntryZip, null)

    @Test
    fun skipPositionsCorrectlyAes() =
        assertSkipPositionsCorrectly("aes256Multi", TestData.aes256MultiZip, "password")

    @Test
    fun skipPositionsCorrectlyLegacy() =
        assertSkipPositionsCorrectly("legacyMulti", TestData.legacyMultiZip, "password")

    /** Plain, no data descriptor — the only fixture that drives the *plain* fast path. */
    @Test
    fun skipPositionsCorrectlyPlainNoDataDescriptor() =
        assertSkipPositionsCorrectly("cliMixed", TestData.cliMixedZip, null)

    /** Walking names only (never reading) yields the same names and terminates cleanly. */
    @Test
    fun walkNamesOnlyMatchesBaseline() {
        for ((data, pw) in listOf(
            TestData.multiEntryZip to null,
            TestData.aes256MultiZip to "password",
            TestData.legacyMultiZip to "password",
        )) {
            val expected = readAll(data, pw).map { it.first }
            val names = mutableListOf<String>()
            open(data, pw).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    names.add(e.name)
                }
            }
            assertEquals(expected, names)
        }
    }

    /** Counts bytes the reader pulls via read() vs skip(), to prove which path ran. */
    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var bytesRead = 0L; private set
        var bytesSkipped = 0L; private set
        override fun read(): Int = inner.read().also { if (it != -1) bytesRead++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) bytesRead += it }
        override fun skip(n: Long): Long = inner.skip(n).also { if (it > 0) bytesSkipped += it }
        override fun close() = inner.close()
    }

    /** Fast path: walking a no-descriptor archive without reading advances via skip(). */
    @Test
    fun untouchedEntriesUseSkip() {
        val counter = CountingInputStream(ByteArrayInputStream(TestData.aes256MultiZip))
        ZipInputStream(counter, "password".encodeToByteArray()).use { zis ->
            while (zis.nextEntry != null) { /* names only — never read entry data */ }
        }
        assertTrue(counter.bytesSkipped > 0, "untouched entries should advance via skip(), not decode")
    }

    /** Fallback: once an entry is partially read, advancing must decode-drain, not skip. */
    @Test
    fun partiallyReadEntryUsesDecodeNotSkip() {
        val counter = CountingInputStream(ByteArrayInputStream(TestData.aes256MultiZip))
        ZipInputStream(counter, "password".encodeToByteArray()).use { zis ->
            zis.nextEntry                  // entry 0
            zis.read(ByteArray(8), 0, 8)   // touch it -> fallback drain on advance
            zis.nextEntry                  // drains entry 0 via read()
            zis.readBytes()                // read entry 1 fully (also no skip)
            assertNull(zis.nextEntry)
        }
        assertEquals(0L, counter.bytesSkipped, "a read entry must not take the skip fast path")
    }

    /** A partially-read entry must fall back to the decode-drain skip and still align. */
    @Test
    fun partialReadThenSkipFallsBack() {
        val baseline = readAll(TestData.aes256MultiZip, "password")
        open(TestData.aes256MultiZip, "password").use { zis ->
            val first = zis.nextEntry
            assertEquals(baseline[0].first, first!!.name)
            // Read only a few bytes of the first entry, then advance.
            zis.read(ByteArray(8), 0, 8)
            val second = zis.nextEntry
            assertEquals(baseline[1].first, second!!.name)
            assertContentEquals(baseline[1].second, zis.readBytes())
            assertNull(zis.nextEntry)
        }
    }
}
