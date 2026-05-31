package no.synth.kmpzip.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import no.synth.kmpzip.io.fileSeekableSource
import no.synth.kmpzip.io.readBytes
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class ZipFileAppleTest {

    private fun writeTempZip(bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "kmpzip-" + bytes.size + ".zip"
        val fp = fopen(path, "wb") ?: error("Cannot create temp file: $path")
        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.toULong(), bytes.size.toULong(), fp)
        }
        fclose(fp)
        return path
    }

    @Test
    fun readsEntryViaFileBackedSource() {
        val path = writeTempZip(TestData.multiEntryZip)
        ZipFile(fileSeekableSource(path)).use { zip ->
            assertEquals(listOf("file1.txt", "file2.txt"), zip.entries.map { it.name })
            assertEquals(
                "Second file content",
                zip.getInputStream("file2.txt").use { it.readBytes().decodeToString() },
            )
        }
    }

    @Test
    fun readsEncryptedEntryViaFileBackedSource() {
        val path = writeTempZip(TestData.aes256MultiZip)
        ZipFile(fileSeekableSource(path), "password").use { zip ->
            assertEquals(
                "Second encrypted file! ".repeat(50),
                zip.getInputStream("second.txt").use { it.readBytes().decodeToString() },
            )
        }
    }
}
