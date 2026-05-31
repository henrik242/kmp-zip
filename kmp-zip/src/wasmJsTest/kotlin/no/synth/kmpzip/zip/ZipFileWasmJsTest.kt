package no.synth.kmpzip.zip

import no.synth.kmpzip.internal.Uint8Array
import no.synth.kmpzip.io.fileSeekableSource
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals

// js() intrinsics must be top-level (Kotlin/Wasm requirement).
private fun tmpDir(): String = js("require('os').tmpdir()")
private fun writeFile(path: String, buf: Uint8Array) { js("require('fs').writeFileSync(path, buf)") }

// Exercises the Node-backed fileSeekableSource at runtime. The browser test task is
// disabled (see build.gradle.kts), so this only ever runs under Node, where `fs`
// and `os` exist.
class ZipFileWasmJsTest {

    private fun writeTempZip(bytes: ByteArray): String {
        val path = tmpDir() + "/kmpzip-" + bytes.size + ".zip"
        writeFile(path, byteArrayToUint8Array(bytes, 0, bytes.size))
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
