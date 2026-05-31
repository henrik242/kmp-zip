package no.synth.kmpzip.okio

import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipFile
import no.synth.kmpzip.zip.ZipOutputStream
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ZipFileAdapterTest {

    private fun multiEntryZipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in listOf("file1.txt" to "First", "file2.txt" to "Second")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.encodeToByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsEntryViaFileHandleSource() {
        val fs = FakeFileSystem()
        val path = "/archive.zip".toPath()
        fs.write(path) { write(multiEntryZipBytes()) }

        ZipFile(fs.openReadOnly(path)).use { zip ->
            assertEquals(listOf("file1.txt", "file2.txt"), zip.entries.map { it.name })
            // Reach the second entry directly, then the first — random access.
            assertEquals("Second", zip.getInputStream("file2.txt").use { it.readBytes().decodeToString() })
            val first = assertNotNull(zip.getEntry("file1.txt"))
            assertEquals("First", zip.getInputStream(first).use { it.readBytes().decodeToString() })
        }
        fs.checkNoOpenFiles()
    }
}
