package no.synth.kmpzip.zip

import no.synth.kmpzip.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LegacyZipTest {

    private val testPassword = "password"

    // ---- Reading macOS zip-created legacy-encrypted ZIPs ----

    @Test
    fun readLegacyStoredEntry() {
        val zis = ZipInputStream(TestData.legacyStoredZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals("Hello, ZipCrypto World!", zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readLegacyDeflatedEntry() {
        val expectedContent = "Hello, ZipCrypto! ".repeat(100)
        val zis = ZipInputStream(TestData.legacyDeflatedZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello-long.txt", entry.name)
        assertEquals(expectedContent, zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readLegacyBinaryEntry() {
        val zis = ZipInputStream(TestData.legacyBinaryZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("raw.bin", entry.name)

        val data = zis.readBytes()
        val expected = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        assertContentEquals(expected, data)

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readLegacyMultiEntry() {
        val zis = ZipInputStream(TestData.legacyMultiZip, testPassword)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("hello.txt", entry1.name)
        assertEquals("Hello, ZipCrypto World!", zis.readBytes().decodeToString())

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("second.txt", entry2.name)
        assertEquals("Second legacy file", zis.readBytes().decodeToString())

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun wrongPasswordReturnsNull() {
        val zis = ZipInputStream(TestData.legacyStoredZip, "wrongpassword")
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun noPasswordReturnsNull() {
        val zis = ZipInputStream(TestData.legacyStoredZip)
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun closeEntrySkipsLegacyData() {
        val zis = ZipInputStream(TestData.legacyMultiZip, testPassword)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        zis.closeEntry()

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("second.txt", entry2.name)
        assertEquals("Second legacy file", zis.readBytes().decodeToString())

        zis.close()
    }

    // ---- Round-trip tests: write legacy-encrypted then read back ----

    @Test
    fun roundTripDeflated() {
        val content = "Hello, legacy encrypted!"
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("hello.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(content, zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripStored() {
        val content = "Hello, stored legacy!"
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        val entry = ZipEntry("stored.txt")
        entry.method = ZipConstants.STORED
        entry.size = content.length.toLong()
        entry.compressedSize = content.length.toLong()
        zos.putNextEntry(entry)
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val readEntry = zis.nextEntry
        assertNotNull(readEntry)
        assertEquals("stored.txt", readEntry.name)
        assertEquals(content, zis.readBytes().decodeToString())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripMultipleEntries() {
        val password = "testpass"
        val entries = listOf(
            "file1.txt" to "First legacy content",
            "file2.txt" to "Second legacy content",
        )

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        for ((name, content) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        for ((expectedName, expectedContent) in entries) {
            val entry = zis.nextEntry
            assertNotNull(entry, "Expected entry: $expectedName")
            assertEquals(expectedName, entry.name)
            assertEquals(expectedContent, zis.readBytes().decodeToString())
        }
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripBinaryContent() {
        val binaryData = ByteArray(256) { it.toByte() }
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("binary.dat"))
        zos.write(binaryData)
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertContentEquals(binaryData, zis.readBytes())
        zis.close()
    }

    @Test
    fun roundTripLargeContent() {
        val largeData = ByteArray(100_000) { (it % 256).toByte() }
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("large.bin"))
        zos.write(largeData)
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertContentEquals(largeData, zis.readBytes())
        zis.close()
    }

    @Test
    fun roundTripWrongPasswordFails() {
        val content = "Secret data"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, "correctpassword".encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("secret.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), "wrongpassword")
        // Wrong password may fail at header check or produce garbage
        val entry = zis.nextEntry
        // Either null (check byte failed) or entry with corrupted data
        if (entry != null) {
            val data = zis.readBytes().decodeToString()
            // Data should not match original
            assertEquals(false, data == content)
        }
        zis.close()
    }

    @Test
    fun roundTripStringPasswordConvenience() {
        val content = "String password test"
        val password = "mypassword"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password, ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("test.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals(content, zis.readBytes().decodeToString())
        zis.close()
    }

    @Test
    fun roundTripEmptyEntry() {
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("empty.txt"))
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("empty.txt", entry.name)
        assertEquals(0, zis.readBytes().size)
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripWriteInChunks() {
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("chunked.txt"))
        zos.write("Hello, ".encodeToByteArray())
        zos.write("legacy ".encodeToByteArray())
        zos.write("world!".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("Hello, legacy world!", zis.readBytes().decodeToString())
        zis.close()
    }

    @Test
    fun roundTripSingleByteReadAndWrite() {
        val password = "testpass"
        val text = "ABC"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("single.txt"))
        for (b in text.encodeToByteArray()) {
            zos.write(b.toInt() and 0xFF)
        }
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)

        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = zis.read()
            if (b == -1) break
            bytes.add(b.toByte())
        }
        assertEquals(text, bytes.toByteArray().decodeToString())
        zis.close()
    }

    @Test
    fun roundTripAutoCloseEntry() {
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), encryption = ZipEncryption.LEGACY)
        zos.putNextEntry(ZipEntry("first.txt"))
        zos.write("First".encodeToByteArray())
        zos.putNextEntry(ZipEntry("second.txt"))
        zos.write("Second".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("First", zis.readBytes().decodeToString())

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("Second", zis.readBytes().decodeToString())

        assertNull(zis.nextEntry)
        zis.close()
    }
}
