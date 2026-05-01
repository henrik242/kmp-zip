package no.synth.kmpzip.sample

import no.synth.kmpzip.zip.ZipPasswordException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Runs every fixture under kmp-zip/src/commonTest/resources/testdata through
// the sample's gunzip / listZipEntries to prove that the Uint8Array
// marshalling shim and the password-retry loop both work end-to-end against
// the real archive bytes.

private val UNENCRYPTED_ZIPS = listOf(
    "stored.zip" to SampleFixtures.storedZip,
    "deflated.zip" to SampleFixtures.deflatedZip,
    "multi-entry.zip" to SampleFixtures.multiEntryZip,
    "directory.zip" to SampleFixtures.directoryZip,
    "empty.zip" to SampleFixtures.emptyZip,
    "binary.zip" to SampleFixtures.binaryZip,
    "cli-stored.zip" to SampleFixtures.cliStoredZip,
    "cli-deflated.zip" to SampleFixtures.cliDeflatedZip,
    "cli-with-dir.zip" to SampleFixtures.cliWithDirZip,
    "cli-mixed.zip" to SampleFixtures.cliMixedZip,
    "seven-stored.zip" to SampleFixtures.sevenStoredZip,
    "seven-deflated.zip" to SampleFixtures.sevenDeflatedZip,
)

private val PASSWORD_FIXTURES = listOf(
    "aes128-deflated.zip" to (SampleFixtures.aes128DeflatedZip to "password"),
    "aes256-binary.zip" to (SampleFixtures.aes256BinaryZip to "password"),
    "aes256-deflated.zip" to (SampleFixtures.aes256DeflatedZip to "password"),
    "aes256-multi.zip" to (SampleFixtures.aes256MultiZip to "password"),
    "aes256-stored.zip" to (SampleFixtures.aes256StoredZip to "password"),
    "zip4j-aes256-deflated.zip" to (SampleFixtures.zip4jAes256DeflatedZip to "123456"),
    "legacy-stored.zip" to (SampleFixtures.legacyStoredZip to "password"),
    "legacy-deflated.zip" to (SampleFixtures.legacyDeflatedZip to "password"),
    "legacy-binary.zip" to (SampleFixtures.legacyBinaryZip to "password"),
    "legacy-multi.zip" to (SampleFixtures.legacyMultiZip to "password"),
)

class SampleFixtureTest {

    @Test
    fun gunzipDecompressesCliGz() {
        val out = gunzip(SampleFixtures.cliGzip.toUint8Array()).toByteArray()
        assertEquals("Hello from gzip CLI", out.decodeToString())
    }

    @Test
    fun listZipEntriesHandlesEveryUnencryptedFixture() {
        for ((name, bytes) in UNENCRYPTED_ZIPS) {
            val listing = listZipEntries(bytes.toUint8Array(), password = null)
            val rows = listing.lines().drop(1).filter { it.isNotBlank() }
            // empty.zip legitimately has no entries — every other fixture should
            // produce at least one row.
            if (name != "empty.zip") {
                assertTrue(rows.isNotEmpty(), "$name yielded no entries")
            }
        }
    }

    @Test
    fun encryptedFixturesThrowWithoutPassword() {
        for ((name, pair) in PASSWORD_FIXTURES) {
            val (bytes, _) = pair
            assertFailsWith<ZipPasswordException>("$name should require a password") {
                listZipEntries(bytes.toUint8Array(), password = null)
            }
        }
    }

    @Test
    fun encryptedFixturesDecryptWithCorrectPassword() {
        for ((name, pair) in PASSWORD_FIXTURES) {
            val (bytes, password) = pair
            val listing = listZipEntries(bytes.toUint8Array(), password)
            val rows = listing.lines().drop(1).filter { it.isNotBlank() }
            assertTrue(rows.isNotEmpty(), "$name yielded no entries with correct password")
        }
    }

    @Test
    fun encryptedFixturesRejectWrongPassword() {
        // Wrong-password failure mode varies between AES (HMAC mismatch) and
        // legacy (decryption succeeds but the deflate stream is garbage). Both
        // surface as ZipPasswordException at the sample boundary.
        for ((name, pair) in PASSWORD_FIXTURES) {
            val (bytes, _) = pair
            assertFailsWith<ZipPasswordException>("$name should reject the wrong password") {
                listZipEntries(bytes.toUint8Array(), password = "this-is-not-the-password")
            }
        }
    }

    @Test
    fun byteArrayMarshallingRoundTrips() {
        val patterns = listOf(
            ByteArray(0),
            ByteArray(1) { 0 },
            ByteArray(256) { it.toByte() },
            ByteArray(1024) { (it xor 0xAA).toByte() },
            ByteArray(50_000) { ((it * 31) and 0xff).toByte() },
        )
        for ((index, expected) in patterns.withIndex()) {
            val actual = expected.toUint8Array().toByteArray()
            assertEquals(expected.size, actual.size, "pattern $index size mismatch")
            assertTrue(expected.contentEquals(actual), "pattern $index content mismatch")
        }
    }
}
