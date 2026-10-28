package no.synth.kmpzip.zip

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

class Zip64Test {

    private fun createZip(block: (ZipOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use(block)
        return baos.toByteArray()
    }

    private fun readEntries(data: ByteArray): List<Pair<ZipEntry, String>> {
        val result = mutableListOf<Pair<ZipEntry, String>>()
        ZipInputStream(data).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                result.add(entry to zis.readBytes().decodeToString())
            }
        }
        return result
    }

    @Test
    fun zipInputStreamReadsInfozipZip64() {
        val entries = readEntries(TestData.infozipZip64Zip)
        assertEquals(listOf("hello.txt", "second.txt"), entries.map { it.first.name })
        assertEquals(listOf("Hello, ZIP64!\n", "Second entry\n"), entries.map { it.second })
        assertEquals(14L, entries[0].first.size)
        assertEquals(14L, entries[0].first.compressedSize)
    }

    @Test
    fun zipFileReadsInfozipZip64() {
        ZipFile(TestData.infozipZip64Zip).use { zip ->
            assertEquals(listOf("hello.txt", "second.txt"), zip.entries.map { it.name })
            val second = assertNotNull(zip.getEntry("second.txt"))
            assertEquals(13L, second.size)
            assertEquals(13L, second.compressedSize)
            assertEquals("Second entry\n", zip.getInputStream(second).use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun zipInputStreamReadsZip64DataDescriptor() {
        val entries = readEntries(TestData.pythonZip64DdZip)
        assertEquals(listOf("data.txt", "small.txt"), entries.map { it.first.name })
        assertEquals("Python force_zip64 entry\n".repeat(20), entries[0].second)
        assertEquals("not zip64\n", entries[1].second)
        // Sizes come from the 8-byte descriptor of the first entry and the 4-byte one of the second.
        assertEquals(500L, entries[0].first.size)
        assertEquals(10L, entries[1].first.size)
    }

    @Test
    fun zipFileReadsZip64DataDescriptor() {
        ZipFile(TestData.pythonZip64DdZip).use { zip ->
            assertEquals(500L, assertNotNull(zip.getEntry("data.txt")).size)
            assertEquals("not zip64\n", zip.getInputStream("small.txt").use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun sizeHintWritesZip64LocalHeader() {
        val content = "small, but declared huge".encodeToByteArray()
        val data = createZip { zos ->
            val entry = ZipEntry("big.bin")
            entry.size = 5L * 1024 * 1024 * 1024
            zos.putNextEntry(entry)
            zos.write(content)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("after.txt"))
            zos.write("after".encodeToByteArray())
        }

        // Local header: version 45, sizes marked, ZIP64 extra field first.
        assertEquals(ZipConstants.VERSION_ZIP64, readLeShort(data, 4))
        assertEquals(ZipConstants.ZIP64_MAGIC, readLeUInt(data, 18))
        assertEquals(ZipConstants.ZIP64_MAGIC, readLeUInt(data, 22))
        val nameLen = readLeShort(data, 26)
        assertEquals(ZipConstants.ZIP64_EXTRA_ID, readLeShort(data, 30 + nameLen))

        val entries = readEntries(data)
        assertEquals(listOf("small, but declared huge", "after"), entries.map { it.second })
        assertEquals(content.size.toLong(), entries[0].first.size)

        ZipFile(data).use { zip ->
            assertEquals(content.size.toLong(), assertNotNull(zip.getEntry("big.bin")).size)
            assertEquals("after", zip.getInputStream("after.txt").use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun smallArchiveHasNoZip64Records() {
        val data = createZip { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("a".encodeToByteArray())
        }
        assertEquals(ZipConstants.VERSION_DEFAULT, readLeShort(data, 4))
        assertEquals(ZipConstants.END_OF_CENTRAL_DIR_SIGNATURE.toLong(), readLeUInt(data, data.size - 22))
        assertTrue(readLeUInt(data, data.size - 42) != ZipConstants.ZIP64_END_OF_CENTRAL_DIR_LOCATOR_SIGNATURE.toLong())
    }

    @Test
    fun manyEntriesWriteZip64EndRecords() {
        // The smallest count that needs ZIP64. Kept to the minimum, as writing this
        // many entries is slow on js, where mocha fails a test after 2 s.
        val count = 0xFFFF
        val data = createZip { zos ->
            zos.setMethod(ZipConstants.STORED)
            for (i in 0 until count) {
                val entry = ZipEntry("e$i")
                entry.size = 0
                entry.crc = 0
                zos.putNextEntry(entry)
            }
        }

        // EOCD count is the marker; the locator sits just before the EOCD.
        val eocd = data.size - 22
        assertEquals(ZipConstants.ZIP64_MAGIC_COUNT, readLeShort(data, eocd + 10))
        assertEquals(ZipConstants.ZIP64_END_OF_CENTRAL_DIR_LOCATOR_SIGNATURE.toLong(), readLeUInt(data, eocd - 20))

        ZipFile(data).use { zip ->
            assertEquals(count, zip.entries.size)
            assertEquals("e${count - 1}", zip.entries.last().name)
        }
    }

    @Test
    fun storedEntryWithoutCompressedSize() {
        val content = "stored without compressedSize".encodeToByteArray()
        val data = createZip { zos ->
            val entry = ZipEntry("s.txt")
            entry.method = ZipConstants.STORED
            entry.size = content.size.toLong()
            entry.crc = Crypto.crc32(content)
            zos.putNextEntry(entry)
            zos.write(content)
            zos.putNextEntry(ZipEntry("next.txt"))
            zos.write("next".encodeToByteArray())
        }
        assertEquals(
            listOf("stored without compressedSize", "next"),
            readEntries(data).map { it.second },
        )
    }

    @Test
    fun staleZip64ExtraFieldIsNotCopied() {
        val other = byteArrayOf(0x34, 0x12, 2, 0, 7, 7) // unknown header 0x1234, 2 bytes
        val data = createZip { zos ->
            val entry = ZipEntry("copied.txt")
            entry.extra = zip64ExtraField(123, 45) + other
            zos.putNextEntry(entry)
            zos.write("copied".encodeToByteArray())
        }
        val entries = readEntries(data)
        assertContentEquals(other, entries[0].first.extra)
        assertEquals("copied", entries[0].second)
        ZipFile(data).use { zip ->
            assertContentEquals(other, zip.entries[0].extra)
        }
    }

    private fun extraOfSize(size: Int): ByteArray {
        // One unknown field (header 0x1234) whose data fills the rest.
        val extra = ByteArray(size)
        extra[0] = 0x34
        extra[1] = 0x12
        extra[2] = ((size - 4) and 0xFF).toByte()
        extra[3] = ((size - 4) ushr 8).toByte()
        return extra
    }

    @Test
    fun extraFieldLeavingRoomForZip64IsWritten() {
        val extra = extraOfSize(0xFFFF - 28)
        val data = createZip { zos ->
            val entry = ZipEntry("e.txt")
            entry.extra = extra
            zos.putNextEntry(entry)
            zos.write("e".encodeToByteArray())
        }
        assertContentEquals(extra, readEntries(data)[0].first.extra)
    }

    @Test
    fun extraFieldWithoutRoomForZip64Rejected() {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        val entry = ZipEntry("e.txt")
        entry.extra = extraOfSize(0xFFFF - 27)
        assertFailsWith<IllegalArgumentException> { zos.putNextEntry(entry) }
        assertEquals(0, baos.size())
    }

    @Test
    fun extraFieldWithoutRoomForAesRejected() {
        val zos = ZipOutputStream(ByteArrayOutputStream(), "pw".encodeToByteArray())
        val entry = ZipEntry("e.txt")
        entry.extra = extraOfSize(0xFFFF - 28)
        assertFailsWith<IllegalArgumentException> { zos.putNextEntry(entry) }
    }

    @Test
    fun centralDirectoryOffsetMarkerWithoutLocatorRejected() {
        val bytes = TestData.multiEntryZip.copyOf()
        // EOCD "central directory offset" (offset +16) -> ZIP64 marker, no locator present.
        for (i in 6 downTo 3) bytes[bytes.size - i] = 0xFF.toByte()
        assertFailsWith<ZipFormatException> { ZipFile(bytes) }
    }

    @Test
    fun missingZip64ExtraFieldRejected() {
        val bytes = TestData.multiEntryZip.copyOf()
        val cdOffset = readLeUInt(bytes, bytes.size - 6).toInt()
        // First central header "uncompressed size" (offset +24) -> marker, no ZIP64 field.
        for (i in 24 until 28) bytes[cdOffset + i] = 0xFF.toByte()
        assertFailsWith<ZipFormatException> { ZipFile(bytes) }
    }

    @Test
    fun noZip64FieldMeansNull() {
        assertNull(Zip64Extra.find(byteArrayOf(0x34, 0x12, 0, 0)))
        assertNull(stripZip64Extra(zip64ExtraField(1)))
    }
}
