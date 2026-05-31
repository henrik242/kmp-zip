package no.synth.kmpzip.zip

import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipFileTest {

    private fun InputStream.readText(): String = readBytes().decodeToString()

    @Test
    fun listsEntriesFromCentralDirectory() {
        ZipFile(TestData.multiEntryZip).use { zip ->
            assertEquals(listOf("file1.txt", "file2.txt"), zip.entries.map { it.name })
        }
    }

    @Test
    fun getEntryReturnsMetadata() {
        ZipFile(TestData.multiEntryZip).use { zip ->
            val entry = zip.getEntry("file1.txt")
            assertNotNull(entry)
            assertEquals("file1.txt", entry.name)
            assertEquals("First file content".length.toLong(), entry.size)
            assertNull(zip.getEntry("does-not-exist.txt"))
        }
    }

    @Test
    fun readsSingleStoredEntry() {
        ZipFile(TestData.storedZip).use { zip ->
            val entry = assertNotNull(zip.getEntry("hello.txt"))
            assertEquals(ZipConstants.STORED, entry.method)
            assertEquals("Hello, World!", zip.getInputStream(entry).use { it.readText() })
        }
    }

    @Test
    fun readsSingleDeflatedEntry() {
        ZipFile(TestData.deflatedZip).use { zip ->
            assertEquals("Hello, World!", zip.getInputStream("hello.txt").use { it.readText() })
        }
    }

    /** The whole point: reach a later entry without reading the earlier ones. */
    @Test
    fun readsSecondEntryDirectly() {
        ZipFile(TestData.multiEntryZip).use { zip ->
            assertEquals("Second file content", zip.getInputStream("file2.txt").use { it.readText() })
            // ...and the first is still readable afterwards, in any order.
            assertEquals("First file content", zip.getInputStream("file1.txt").use { it.readText() })
        }
    }

    @Test
    fun readsAesEncryptedEntryByName() {
        ZipFile(TestData.aes256MultiZip, "password").use { zip ->
            assertEquals(
                "Second encrypted file! ".repeat(50),
                zip.getInputStream("second.txt").use { it.readText() },
            )
        }
    }

    @Test
    fun readsLegacyEncryptedEntryByName() {
        ZipFile(TestData.legacyMultiZip, "password").use { zip ->
            val name = zip.entries.last().name
            // Smoke test: decrypts without throwing and yields non-empty content.
            assertTrue(zip.getInputStream(name).use { it.readBytes() }.isNotEmpty())
        }
    }

    @Test
    fun missingEntryThrows() {
        ZipFile(TestData.multiEntryZip).use { zip ->
            assertFails { zip.getInputStream("nope.txt") }
        }
    }

    @Test
    fun notAZipThrows() {
        assertFails { ZipFile(ByteArray(100) { 0 }) }
    }

    // The test archives carry no ZIP comment, so the EOCD is exactly the last 22 bytes.
    @Test
    fun splitArchiveRejected() {
        val bytes = TestData.multiEntryZip.copyOf()
        bytes[bytes.size - 18] = 1 // EOCD "number of this disk" (offset +4) -> nonzero
        assertFails { ZipFile(bytes) }
    }

    @Test
    fun truncatedCentralDirectoryRejected() {
        val bytes = TestData.multiEntryZip.copyOf()
        bytes[bytes.size - 12] = 9 // EOCD "total entries" (offset +10) -> declares more than present
        assertFails { ZipFile(bytes) }
    }
}
