package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AesZipTest {

    private val testPassword = "password"

    private fun readEntryContent(zis: ZipInputStream): String {
        return zis.readBytes().decodeToString()
    }

    // ---- Reading externally-created AES-encrypted ZIPs ----

    @Test
    fun readAes256StoredEntry() {
        val zis = ZipInputStream(TestData.aes256StoredZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.STORED, entry.method)
        assertEquals("Hello, AES World\\!", readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readAes256DeflatedEntry() {
        val expectedContent = "Hello, AES World! ".repeat(100)
        val zis = ZipInputStream(TestData.aes256DeflatedZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.DEFLATED, entry.method)
        assertEquals(expectedContent, readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readAes128DeflatedEntry() {
        val expectedContent = "Hello, AES World! ".repeat(100)
        val zis = ZipInputStream(TestData.aes128DeflatedZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.DEFLATED, entry.method)
        assertEquals(expectedContent, readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readAes256BinaryEntry() {
        val zis = ZipInputStream(TestData.aes256BinaryZip, testPassword)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("raw.bin", entry.name)
        assertEquals(ZipConstants.STORED, entry.method)

        val data = zis.readBytes()
        assertEquals(7, data.size)
        val expected = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        assertContentEquals(expected, data)

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readAes256MultiEntry() {
        val zis = ZipInputStream(TestData.aes256MultiZip, testPassword)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("hello.txt", entry1.name)
        assertEquals("Hello, AES World! ".repeat(100), readEntryContent(zis))

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("second.txt", entry2.name)
        assertEquals("Second encrypted file! ".repeat(50), readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun wrongPasswordReturnsNull() {
        // nextEntry catches the password verification failure and returns null
        val zis = ZipInputStream(TestData.aes256StoredZip, "wrongpassword")
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun noPasswordReturnsNull() {
        // nextEntry catches the "password required" exception and returns null
        val zis = ZipInputStream(TestData.aes256StoredZip)
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun closeEntrySkipsEncryptedData() {
        val zis = ZipInputStream(TestData.aes256MultiZip, testPassword)

        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        // Don't read, just close entry
        zis.closeEntry()

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("second.txt", entry2.name)
        assertEquals("Second encrypted file! ".repeat(50), readEntryContent(zis))

        zis.close()
    }

    // ---- Round-trip tests: write encrypted then read back ----

    @Test
    fun roundTripDeflated() {
        val content = "Hello, encrypted world!"
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("hello.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("hello.txt", entry.name)
        assertEquals(ZipConstants.DEFLATED, entry.method)
        assertEquals(content, readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripStored() {
        val content = "Hello, stored encrypted!"
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
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
        assertEquals(ZipConstants.STORED, readEntry.method)
        assertEquals(content, readEntryContent(zis))
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripMultipleEntries() {
        val password = "testpass"
        val entries = listOf(
            "file1.txt" to "First encrypted content",
            "file2.txt" to "Second encrypted content",
            "file3.txt" to "Third encrypted content",
        )

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
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
            assertEquals(expectedContent, readEntryContent(zis))
        }
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripBinaryContent() {
        val binaryData = ByteArray(256) { it.toByte() }
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("binary.dat"))
        zos.write(binaryData)
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("binary.dat", entry.name)
        assertContentEquals(binaryData, zis.readBytes())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripLargeContent() {
        val largeData = ByteArray(100_000) { (it % 256).toByte() }
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("large.bin"))
        zos.write(largeData)
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertContentEquals(largeData, zis.readBytes())
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripAes128() {
        val content = "AES-128 content"
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), AesStrength.AES_128)
        zos.putNextEntry(ZipEntry("aes128.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals(content, readEntryContent(zis))
        zis.close()
    }

    @Test
    fun roundTripAes192() {
        val content = "AES-192 content"
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), AesStrength.AES_192)
        zos.putNextEntry(ZipEntry("aes192.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals(content, readEntryContent(zis))
        zis.close()
    }

    @Test
    fun roundTripEmptyEntry() {
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
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
    fun roundTripStringPassword() {
        val content = "String password test"
        val password = "mypassword"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password)
        zos.putNextEntry(ZipEntry("test.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals(content, readEntryContent(zis))
        zis.close()
    }

    @Test
    fun roundTripWrongPasswordFails() {
        val content = "Secret data"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, "correctpassword".encodeToByteArray())
        zos.putNextEntry(ZipEntry("secret.txt"))
        zos.write(content.encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), "wrongpassword")
        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun roundTripWriteInChunks() {
        val password = "testpass"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("chunked.txt"))
        zos.write("Hello, ".encodeToByteArray())
        zos.write("encrypted ".encodeToByteArray())
        zos.write("world!".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("Hello, encrypted world!", readEntryContent(zis))
        zis.close()
    }

    @Test
    fun roundTripSingleByteReadAndWrite() {
        val password = "testpass"
        val text = "ABC"

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("single.txt"))
        for (b in text.encodeToByteArray()) {
            zos.write(b.toInt() and 0xFF)
        }
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry = zis.nextEntry
        assertNotNull(entry)

        // Read single bytes
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
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("first.txt"))
        zos.write("First".encodeToByteArray())
        // Don't explicitly closeEntry - putNextEntry should handle it
        zos.putNextEntry(ZipEntry("second.txt"))
        zos.write("Second".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val zis = ZipInputStream(baos.toByteArray(), password)
        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("First", readEntryContent(zis))

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("Second", readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }
}
