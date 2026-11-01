package no.synth.kmpzip.zip

import no.synth.kmpzip.io.fileSeekableSource
import no.synth.kmpzip.internal.byteArrayToUint8Array
import no.synth.kmpzip.io.readBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Exercises the Node-backed fileSeekableSource at runtime. The browser test task is
// disabled (see build.gradle.kts), so this only ever runs under Node, where `fs`
// and `os` exist.
class ZipFileNodeTest {

    private fun writeTempZip(bytes: ByteArray): String {
        // js and wasmJs run this same test, so keep the paths from colliding.
        val path = tmpdir() + "/kmpzip-" + bytes.size + "-" + Random.nextInt(Int.MAX_VALUE) + ".zip"
        writeFileSync(path, byteArrayToUint8Array(bytes, 0, bytes.size))
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
