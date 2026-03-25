package no.synth.kmpzip.zip

import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun readZip4jAes256DeflatedEntry() {
        // Zip4j uses data descriptors (bit 3) with compressedSize=0 in local header
        val zis = ZipInputStream(TestData.zip4jAes256DeflatedZip, "123456")
        val entry = zis.nextEntry
        assertNotNull(entry)
        assertEquals("digital.json", entry.name)
        assertEquals(ZipConstants.DEFLATED, entry.method)
        val content = readEntryContent(zis)
        assertEquals(343, content.encodeToByteArray().size)
        assertTrue(content.startsWith("{\"KEY_EVENTINFO\""))
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
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), aesStrength = AesStrength.AES_128)
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
        val zos = ZipOutputStream(baos, password.encodeToByteArray(), aesStrength = AesStrength.AES_192)
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

    // ---- Tests for data descriptor handling (Zip4j-style) ----

    @Test
    fun readMultiEntryWithDataDescriptors() {
        // Create a normal 2-entry AES zip, then convert to data descriptor format
        val password = "testpass"
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("first.txt"))
        zos.write("First encrypted entry content".encodeToByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("second.txt"))
        zos.write("Second encrypted entry content".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val dataDescriptorZip = addDataDescriptors(baos.toByteArray())

        val zis = ZipInputStream(dataDescriptorZip, password)
        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        assertEquals("first.txt", entry1.name)
        assertEquals("First encrypted entry content", readEntryContent(zis))

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("second.txt", entry2.name)
        assertEquals("Second encrypted entry content", readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }

    @Test
    fun readDataDescriptorSkipFirstEntry() {
        // Verify that skipping (closeEntry without reading) works with data descriptors
        val password = "testpass"
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos, password.encodeToByteArray())
        zos.putNextEntry(ZipEntry("skipped.txt"))
        zos.write("This entry will be skipped".encodeToByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("read.txt"))
        zos.write("This entry will be read".encodeToByteArray())
        zos.closeEntry()
        zos.close()

        val dataDescriptorZip = addDataDescriptors(baos.toByteArray())

        val zis = ZipInputStream(dataDescriptorZip, password)
        val entry1 = zis.nextEntry
        assertNotNull(entry1)
        // Skip first entry without reading
        zis.closeEntry()

        val entry2 = zis.nextEntry
        assertNotNull(entry2)
        assertEquals("read.txt", entry2.name)
        assertEquals("This entry will be read", readEntryContent(zis))

        assertNull(zis.nextEntry)
        zis.close()
    }

    companion object {
        /**
         * Converts a kmp-zip-generated AES zip to use data descriptors, mimicking
         * how Zip4j writes AES entries (flag bit 3 set, sizes=0 in local header,
         * data descriptor after entry data).
         */
        fun addDataDescriptors(zip: ByteArray): ByteArray {
            data class EntryInfo(
                val headerStart: Int, val dataStart: Int,
                val crc: Long, val compSize: Int, val uncSize: Int,
            )

            // Phase 1: parse local file headers to find entry boundaries
            val entries = mutableListOf<EntryInfo>()
            var pos = 0
            while (pos + 30 <= zip.size && readLe32(zip, pos) == 0x04034b50L) {
                val nameLen = readLe16(zip, pos + 26)
                val extraLen = readLe16(zip, pos + 28)
                val compSize = readLe32(zip, pos + 18).toInt()
                entries.add(EntryInfo(
                    headerStart = pos,
                    dataStart = pos + 30 + nameLen + extraLen,
                    crc = readLe32(zip, pos + 14),
                    compSize = compSize,
                    uncSize = readLe32(zip, pos + 22).toInt(),
                ))
                pos += 30 + nameLen + extraLen + compSize
            }
            val centralDirStart = pos

            // Phase 2: build new zip with data descriptors inserted
            val out = ByteArrayOutputStream()
            val newOffsets = mutableListOf<Int>()
            var totalInserted = 0

            for (entry in entries) {
                newOffsets.add(entry.headerStart + totalInserted)

                // Copy local file header with modifications
                val header = zip.copyOfRange(entry.headerStart, entry.dataStart)
                header[6] = (header[6].toInt() or 0x08).toByte() // set data descriptor flag
                for (i in 14..25) header[i] = 0 // zero CRC + compressedSize + uncompressedSize
                out.write(header)

                // Copy entry data unchanged
                out.write(zip, entry.dataStart, entry.compSize)

                // Insert data descriptor: signature + CRC + compressedSize + uncompressedSize
                val dd = ByteArray(16)
                writeLe32(dd, 0, 0x08074b50L)
                writeLe32(dd, 4, entry.crc)
                writeLe32(dd, 8, entry.compSize.toLong())
                writeLe32(dd, 12, entry.uncSize.toLong())
                out.write(dd)
                totalInserted += 16
            }

            // Phase 3: copy central directory with adjusted offsets
            var cdPos = centralDirStart
            for (i in entries.indices) {
                if (cdPos + 46 > zip.size || readLe32(zip, cdPos) != 0x02014b50L) break
                val nameLen = readLe16(zip, cdPos + 28)
                val extraLen = readLe16(zip, cdPos + 30)
                val commentLen = readLe16(zip, cdPos + 32)
                val cdEntrySize = 46 + nameLen + extraLen + commentLen

                val cdEntry = zip.copyOfRange(cdPos, cdPos + cdEntrySize)
                cdEntry[8] = (cdEntry[8].toInt() or 0x08).toByte() // set data descriptor flag
                writeLe32(cdEntry, 42, newOffsets[i].toLong()) // adjust local header offset
                out.write(cdEntry)
                cdPos += cdEntrySize
            }

            // Phase 4: copy EOCD with adjusted central directory offset
            if (cdPos + 22 <= zip.size && readLe32(zip, cdPos) == 0x06054b50L) {
                val eocd = zip.copyOfRange(cdPos, zip.size)
                writeLe32(eocd, 16, readLe32(zip, cdPos + 16) + totalInserted)
                out.write(eocd)
            }

            return out.toByteArray()
        }

        private fun readLe16(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

        private fun readLe32(data: ByteArray, off: Int): Long =
            (data[off].toInt() and 0xFF).toLong() or
                ((data[off + 1].toInt() and 0xFF).toLong() shl 8) or
                ((data[off + 2].toInt() and 0xFF).toLong() shl 16) or
                ((data[off + 3].toInt() and 0xFF).toLong() shl 24)

        private fun writeLe32(data: ByteArray, off: Int, value: Long) {
            data[off] = (value and 0xFF).toByte()
            data[off + 1] = ((value shr 8) and 0xFF).toByte()
            data[off + 2] = ((value shr 16) and 0xFF).toByte()
            data[off + 3] = ((value shr 24) and 0xFF).toByte()
        }
    }
}
