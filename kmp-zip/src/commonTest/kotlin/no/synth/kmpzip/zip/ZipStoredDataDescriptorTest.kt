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

class ZipStoredDataDescriptorTest {

    private val content = "Stored content, followed by a data descriptor\n".repeat(4).encodeToByteArray()

    private fun storedZip(password: String? = null, content: ByteArray = this.content): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = if (password == null) {
            ZipOutputStream(baos)
        } else {
            ZipOutputStream(baos, password, ZipEncryption.LEGACY)
        }
        zos.use {
            val entry = ZipEntry("stored.txt")
            entry.method = ZipConstants.STORED
            entry.size = content.size.toLong()
            entry.crc = Crypto.crc32(content)
            it.putNextEntry(entry)
            it.write(content)
        }
        return baos.toByteArray()
    }

    private fun putLeInt(data: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) data[at + i] = (v ushr (8 * i)).toByte()
    }

    /**
     * Rewrites a single-entry archive with a data descriptor after the entry data: sets
     * bit 3 in both headers and, if [zeroSizes], clears the local header sizes (and CRC,
     * if [zeroCrc]) so a reader has to find the end of the data another way.
     */
    private fun withDataDescriptor(
        zip: ByteArray,
        zeroSizes: Boolean,
        zeroCrc: Boolean = zeroSizes,
        signature: Boolean = true,
    ): ByteArray {
        val crc = readLeUInt(zip, 14)
        val compressedSize = readLeUInt(zip, 18)
        val size = readLeUInt(zip, 22)
        val dataEnd = 30 + readLeShort(zip, 26) + readLeShort(zip, 28) + compressedSize.toInt()
        val withSignature = ByteArray(16)
        putLeInt(withSignature, 0, ZipConstants.DATA_DESCRIPTOR_SIGNATURE.toLong())
        putLeInt(withSignature, 4, crc)
        putLeInt(withSignature, 8, compressedSize)
        putLeInt(withSignature, 12, size)
        val descriptor = if (signature) withSignature else withSignature.copyOfRange(4, 16)
        val out = zip.copyOfRange(0, dataEnd) + descriptor + zip.copyOfRange(dataEnd, zip.size)

        out[6] = (out[6].toInt() or 0x08).toByte()
        if (zeroCrc) putLeInt(out, 14, 0)
        if (zeroSizes) {
            putLeInt(out, 18, 0)
            putLeInt(out, 22, 0)
        }
        val eocd = out.size - 22
        val cd = readLeUInt(out, eocd + 16) + descriptor.size
        putLeInt(out, eocd + 16, cd)
        out[cd.toInt() + 8] = (out[cd.toInt() + 8].toInt() or 0x08).toByte()
        return out
    }

    private fun readAll(data: ByteArray, password: String? = null): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val zis = if (password == null) ZipInputStream(data) else ZipInputStream(data, password)
        zis.use {
            while (true) {
                val entry = it.nextEntry ?: break
                result.add(entry.name to it.readBytes().decodeToString())
            }
        }
        return result
    }

    private val pythonEntries = listOf(
        "first.txt" to "Stored with a data descriptor\n".repeat(3),
        "empty.txt" to "",
        "last.txt" to "PK\u0007\u0008 looks like a descriptor signature\n",
    )

    @Test
    fun zipInputStreamReadsPythonStoredEntries() {
        assertEquals(pythonEntries, readAll(TestData.pythonStoredDdZip))
    }

    @Test
    fun zipFileReadsPythonStoredEntries() {
        ZipFile(TestData.pythonStoredDdZip).use { zip ->
            for ((name, text) in pythonEntries) {
                assertEquals(text, zip.getInputStream(name).use { it.readBytes().decodeToString() })
            }
        }
    }

    @Test
    fun sizesInLocalHeaderAreUsed() {
        assertEquals(listOf("stored.txt" to content.decodeToString()), readAll(withDataDescriptor(storedZip(), zeroSizes = false)))
    }

    @Test
    fun missingSizesAreFoundFromTheDescriptor() {
        assertEquals(listOf("stored.txt" to content.decodeToString()), readAll(withDataDescriptor(storedZip(), zeroSizes = true)))
    }

    @Test
    fun legacyEncryptedMissingSizesAreFoundFromTheDescriptor() {
        val data = withDataDescriptor(storedZip("pw"), zeroSizes = true, zeroCrc = false)
        assertEquals(listOf("stored.txt" to content.decodeToString()), readAll(data, "pw"))
        ZipFile(data, "pw").use { zip ->
            assertEquals(content.decodeToString(), zip.getInputStream("stored.txt").use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun descriptorContradictingTheDataRejected() {
        val data = withDataDescriptor(storedZip(), zeroSizes = false)
        val dataEnd = 30 + "stored.txt".length + content.size
        putLeInt(data, dataEnd + 12, content.size + 1L) // descriptor uncompressed size
        assertFailsWith<ZipFormatException> { readAll(data) }
    }

    @Test
    fun payloadThatLooksLikeADescriptorIsKept() {
        // A descriptor signature and twelve zero bytes: an empty entry's descriptor, if
        // nothing checked that a header follows it.
        val tricky = byteArrayOf(0x50, 0x4b, 0x07, 0x08) + ByteArray(12) + "and the rest".encodeToByteArray()
        val data = withDataDescriptor(storedZip(content = tricky), zeroSizes = true)
        ZipInputStream(data).use { zis ->
            assertNotNull(zis.nextEntry)
            assertContentEquals(tricky, zis.readBytes())
            assertNull(zis.nextEntry)
        }
    }

    // The DEFLATED entries' read-ahead holds later entries when the STORED ones after them
    // are scanned for their descriptors; the scan must keep what it does not reach.
    @Test
    fun mixedMethodsWithDescriptors() {
        val expected = listOf("a.txt" to 15L, "stored.txt" to 13L, "c.bin" to 10_000L, "d.txt" to 12L)
        val crcs = listOf(0xf1f886f4L, 0x9f28c552L, 0x1e3d49d1L, 0xb6af2396L)
        val read = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(TestData.pythonMixedDdZip).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                read.add(entry.name to zis.readBytes())
            }
        }
        assertEquals(expected, read.map { it.first to it.second.size.toLong() })
        assertEquals(crcs, read.map { Crypto.crc32(it.second) })
        ZipFile(TestData.pythonMixedDdZip).use { zip ->
            assertEquals(crcs[2], Crypto.crc32(zip.getInputStream("c.bin").use { it.readBytes() }))
            assertEquals("last stored\n", zip.getInputStream("d.txt").use { it.readBytes().decodeToString() })
        }
    }

    // The central directory's size is authoritative, zero included, so ZipFile does not
    // scan for the descriptor of an empty entry, which here also lacks its signature.
    @Test
    fun emptyEntryWithUnsignedDescriptorViaZipFile() {
        val data = withDataDescriptor(storedZip(content = ByteArray(0)), zeroSizes = true, signature = false)
        ZipFile(data).use { zip ->
            assertEquals(0, zip.getInputStream("stored.txt").use { it.readBytes() }.size)
        }
    }

    @Test
    fun stopsAtCentralDirectory() {
        ZipInputStream(withDataDescriptor(storedZip(), zeroSizes = true)).use { zis ->
            assertNotNull(zis.nextEntry)
            zis.readBytes()
            assertNull(zis.nextEntry)
        }
    }
}
