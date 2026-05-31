package no.synth.kmpzip.zip

import no.synth.kmpzip.io.fileSeekableSource
import no.synth.kmpzip.io.readBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ZipFileJvmTest {

    private fun writeTempZip(bytes: ByteArray): String {
        val f = File.createTempFile("kmpzip", ".zip")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f.absolutePath
    }

    @Test
    fun readsEntryViaFileBackedSource() {
        val path = writeTempZip(TestData.multiEntryZip)
        ZipFile(fileSeekableSource(path)).use { zip ->
            assertEquals(listOf("file1.txt", "file2.txt"), zip.entries.map { it.name })
            // Reach the second entry directly without reading the first off disk.
            assertEquals("Second file content", zip.getInputStream("file2.txt").use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun readsEncryptedEntryViaFileBackedSource() {
        val path = writeTempZip(TestData.aes256MultiZip)
        ZipFile(fileSeekableSource(path), "password").use { zip ->
            val entry = assertNotNull(zip.getEntry("second.txt"))
            assertEquals(
                "Second encrypted file! ".repeat(50),
                zip.getInputStream(entry).use { it.readBytes().decodeToString() },
            )
        }
    }
}
