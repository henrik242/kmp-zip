package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.crypto.Crypto
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingEncryptionTest {

    private val password = "secret"

    // Larger than the reader's and writer's internal buffers, and not trivially compressible.
    private val big = ByteArray(100_000) { i -> ((i * 7919) xor (i ushr 5)).toByte() }
    private val small = "small entry".encodeToByteArray()

    private class Spec(
        val name: String,
        val encryption: ZipEncryption,
        val method: Int,
        val strength: AesStrength = AesStrength.AES_256,
        val aesVersion: Int = 2,
        val setSize: Boolean = false,
        val setCrc: Boolean = false,
    ) {
        override fun toString() = name
    }

    private val specs = listOf(
        Spec("aes deflated", ZipEncryption.AES, ZipConstants.DEFLATED),
        Spec("aes-128 deflated", ZipEncryption.AES, ZipConstants.DEFLATED, AesStrength.AES_128),
        Spec("aes-192 deflated ae-1", ZipEncryption.AES, ZipConstants.DEFLATED, AesStrength.AES_192, aesVersion = 1),
        Spec("aes stored", ZipEncryption.AES, ZipConstants.STORED),
        Spec("aes stored sized", ZipEncryption.AES, ZipConstants.STORED, setSize = true),
        Spec("aes stored sized ae-1", ZipEncryption.AES, ZipConstants.STORED, aesVersion = 1, setSize = true, setCrc = true),
        Spec("legacy deflated", ZipEncryption.LEGACY, ZipConstants.DEFLATED),
        Spec("legacy stored", ZipEncryption.LEGACY, ZipConstants.STORED),
        Spec("legacy stored sized", ZipEncryption.LEGACY, ZipConstants.STORED, setSize = true, setCrc = true),
    )

    private fun newEntry(name: String, data: ByteArray, spec: Spec): ZipEntry {
        val entry = ZipEntry(name)
        entry.method = spec.method
        if (spec.setSize) entry.size = data.size.toLong()
        if (spec.setCrc) entry.crc = Crypto.crc32(data)
        return entry
    }

    private fun write(spec: Spec, vararg files: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos, password.encodeToByteArray(), spec.encryption, spec.strength, spec.aesVersion).use { zos ->
            for ((name, data) in files) {
                zos.putNextEntry(newEntry(name, data, spec))
                zos.write(data)
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun roundTripsThroughBothReaders() {
        for (spec in specs) {
            val data = write(spec, "big.bin" to big, "small.txt" to small)
            ZipInputStream(data, password).use { zis ->
                assertEquals("big.bin", assertNotNull(zis.nextEntry, "$spec").name)
                assertContentEquals(big, zis.readBytes(), "$spec")
                assertEquals("small.txt", assertNotNull(zis.nextEntry, "$spec").name)
                assertContentEquals(small, zis.readBytes(), "$spec")
                assertNull(zis.nextEntry, "$spec")
            }
            ZipFile(data, password).use { zip ->
                assertContentEquals(small, zip.getInputStream("small.txt").use { it.readBytes() }, "$spec")
                assertContentEquals(big, zip.getInputStream("big.bin").use { it.readBytes() }, "$spec")
                assertEquals(big.size.toLong(), assertNotNull(zip.getEntry("big.bin")).size, "$spec")
            }
        }
    }

    @Test
    fun skipsUnreadEntries() {
        for (spec in specs) {
            val data = write(spec, "big.bin" to big, "small.txt" to small)
            ZipInputStream(data, password).use { zis ->
                zis.nextEntry
                assertEquals("small.txt", assertNotNull(zis.nextEntry, "$spec").name)
                assertContentEquals(small, zis.readBytes(), "$spec")
            }
        }
    }

    @Test
    fun sizesGoInTheHeaderOnlyWhenKnownUpFront() {
        for (spec in specs) {
            val data = write(spec, "big.bin" to big)
            val flag = readLeShort(data, 6)
            val sizedUpFront = spec.method == ZipConstants.STORED && spec.setSize
            assertEquals(if (sizedUpFront) 0 else 0x08, flag and 0x08, "$spec descriptor flag")
            assertEquals(0x01, flag and 0x01, "$spec encrypted flag")
            if (sizedUpFront) {
                val overhead = if (spec.encryption == ZipEncryption.AES) spec.strength.saltLength + 2 + 10 else 12
                assertEquals(big.size.toLong() + overhead, readLeUInt(data, 18), "$spec compressed size")
                assertEquals(big.size.toLong(), readLeUInt(data, 22), "$spec size")
            } else {
                assertEquals(0L, readLeUInt(data, 18), "$spec compressed size")
            }
        }
    }

    @Test
    fun entryDataReachesTheOutputBeforeItIsClosed() {
        for (spec in specs) {
            val baos = ByteArrayOutputStream()
            val zos = ZipOutputStream(baos, password.encodeToByteArray(), spec.encryption, spec.strength, spec.aesVersion)
            zos.setLevel(0)
            zos.putNextEntry(newEntry("big.bin", big, spec))
            zos.write(big)
            assertTrue(baos.size() > big.size / 2, "$spec: only ${baos.size()} bytes written before closeEntry")
            zos.close()
        }
    }

    // A small DEFLATED entry's read-ahead (16 KiB) holds the rest of the archive, and goes
    // back to the input when the entry ends. The unsized STORED entry after it is scanned
    // for its descriptor 4 KiB at a time from there; the scan must keep what it did not
    // reach, the 10 KiB entry after it.
    @Test
    fun storedEntryAfterDeflatedReadAhead() {
        val tenK = big.copyOf(10_000)
        val files = listOf("a.txt" to small, "stored.txt" to small, "c.bin" to tenK, "d.txt" to small)
        for (encryption in ZipEncryption.entries) {
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos, password.encodeToByteArray(), encryption).use { zos ->
                zos.setLevel(0)
                for ((name, data) in files) {
                    val method = if (name == "stored.txt") ZipConstants.STORED else ZipConstants.DEFLATED
                    zos.putNextEntry(newEntry(name, data, Spec(name, encryption, method)))
                    zos.write(data)
                }
            }
            val read = mutableListOf<Pair<String, ByteArray>>()
            ZipInputStream(baos.toByteArray(), password).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    read.add(entry.name to zis.readBytes())
                }
            }
            assertEquals(files.map { it.first }, read.map { it.first }, "$encryption")
            for ((expected, actual) in files.zip(read)) {
                assertContentEquals(expected.second, actual.second, "$encryption ${expected.first}")
            }
        }
    }

    // Readers report AE-2's absent CRC as 0, so copying such an entry must not fail on it.
    @Test
    fun copiesAe2StoredEntry() {
        val source = write(Spec("aes stored sized", ZipEncryption.AES, ZipConstants.STORED, setSize = true), "a.txt" to small)
        val copy = ByteArrayOutputStream()
        ZipFile(source, password).use { zip ->
            val entry = zip.entries.single()
            assertEquals(0L, entry.crc)
            ZipOutputStream(copy, password).use { zos ->
                zos.putNextEntry(entry)
                zip.getInputStream(entry).use { input -> zos.write(input.readBytes()) }
            }
        }
        assertContentEquals(small, ZipFile(copy.toByteArray(), password).use { zip -> zip.getInputStream("a.txt").use { it.readBytes() } })
    }

    @Test
    fun legacyDescriptorEntryUsesTimeCheckByte() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos, password, ZipEncryption.LEGACY).use { zos ->
            val entry = ZipEntry("a.txt")
            // 2024-05-17 17:33:02 in DOS format; a nonzero time high byte, unlike the default.
            entry.time = (0x58B1L shl 16) or 0x8C21L
            zos.putNextEntry(entry)
            zos.write(small)
        }
        val data = baos.toByteArray()
        // With a descriptor the CRC is not known when the header is written, so a reader
        // has only the time to check the password against.
        val wrong = data.copyOf()
        wrong[11] = (wrong[11] + 1).toByte() // high byte of the DOS time
        assertFailsWith<ZipPasswordException> { ZipInputStream(wrong, password).use { it.nextEntry } }
        assertContentEquals(small, ZipInputStream(data, password).use { it.nextEntry; it.readBytes() })
    }

    @Test
    fun wrongPasswordRejected() {
        for (spec in specs.filter { it.encryption == ZipEncryption.AES }) {
            val data = write(spec, "a.txt" to small)
            assertFailsWith<ZipPasswordException>("$spec") { ZipInputStream(data, "nope").use { it.nextEntry } }
        }
    }

    @Test
    fun corruptedCiphertextFailsAuthentication() {
        val data = write(Spec("aes deflated", ZipEncryption.AES, ZipConstants.DEFLATED), "big.bin" to big)
        // Flip a byte well inside the encrypted data: past the header, salt and check value.
        data[30 + "big.bin".length + 11 + 16 + 2 + 1000] = (data[30 + "big.bin".length + 11 + 16 + 2 + 1000].toInt() xor 1).toByte()
        assertFailsWith<ZipException> { ZipInputStream(data, password).use { it.nextEntry; it.readBytes() } }
    }

    @Test
    fun entryFieldsAreSetOnClose() {
        val baos = ByteArrayOutputStream()
        val entry = ZipEntry("a.txt")
        ZipOutputStream(baos, password).use { zos ->
            zos.putNextEntry(entry)
            zos.write(small)
        }
        assertEquals(small.size.toLong(), entry.size)
        assertEquals(Crypto.crc32(small), entry.crc)
        assertTrue(entry.compressedSize > 0)
    }

    @Test
    fun storedSizeMismatchRejected() {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password)
        val entry = ZipEntry("a.txt")
        entry.method = ZipConstants.STORED
        entry.size = small.size + 1L
        zos.putNextEntry(entry)
        zos.write(small)
        assertFailsWith<IllegalStateException> { zos.closeEntry() }

        // The failed entry is dropped from the central directory; the archive still closes.
        zos.putNextEntry(ZipEntry("b.txt"))
        zos.write(small)
        zos.close()
        ZipFile(baos.toByteArray(), password).use { zip ->
            assertEquals(listOf("b.txt"), zip.entries.map { it.name })
            assertContentEquals(small, zip.getInputStream("b.txt").use { it.readBytes() })
        }
    }

    @Test
    fun storedCrcMismatchRejected() {
        val zos = ZipOutputStream(ByteArrayOutputStream(), password.encodeToByteArray(), ZipEncryption.LEGACY)
        val entry = ZipEntry("a.txt")
        entry.method = ZipConstants.STORED
        entry.size = small.size.toLong()
        entry.crc = Crypto.crc32(small) xor 1
        zos.putNextEntry(entry)
        zos.write(small)
        assertFailsWith<IllegalStateException> { zos.closeEntry() }
    }
}
