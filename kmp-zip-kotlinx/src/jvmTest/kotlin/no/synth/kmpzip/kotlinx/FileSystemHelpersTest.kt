package no.synth.kmpzip.kotlinx

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileSystemHelpersTest {

    private val tmpRoot = createTempDirectory("kmpzip-fs-test-")

    @AfterTest
    fun cleanup() {
        tmpRoot.toFile().deleteRecursively()
    }

    private fun path(vararg parts: String): Path = Path(tmpRoot.toString(), *parts)

    private fun writeFile(p: Path, contents: String) {
        p.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(p).buffered().use { it.writeString(contents) }
    }

    private fun readFile(p: Path): String =
        SystemFileSystem.source(p).buffered().use { it.readString() }

    @Test
    fun roundTripSingleFile() = runTest {
        val src = path("hello.txt")
        val zip = path("out", "archive.zip")
        val dest = path("extracted")

        writeFile(src, "Hello, FS!")

        SystemFileSystem.zipTo(zip, listOf(src))
        SystemFileSystem.unzipFrom(zip, dest)

        assertEquals("Hello, FS!", readFile(Path(dest, "hello.txt")))
    }

    @Test
    fun roundTripDirectory() = runTest {
        val srcDir = path("data")
        val zip = path("out", "archive.zip")
        val dest = path("extracted")

        writeFile(Path(srcDir, "a.txt"), "alpha")
        writeFile(Path(srcDir, "b.txt"), "beta")
        writeFile(Path(srcDir, "nested", "c.txt"), "gamma")

        SystemFileSystem.zipTo(zip, listOf(srcDir))
        SystemFileSystem.unzipFrom(zip, dest)

        assertEquals("alpha", readFile(Path(dest, "data", "a.txt")))
        assertEquals("beta", readFile(Path(dest, "data", "b.txt")))
        assertEquals("gamma", readFile(Path(dest, "data", "nested", "c.txt")))
    }

    @Test
    fun roundTripMultipleSources() = runTest {
        val a = path("a.txt")
        val b = path("b.txt")
        val zip = path("out", "archive.zip")
        val dest = path("extracted")

        writeFile(a, "A")
        writeFile(b, "B")

        SystemFileSystem.zipTo(zip, listOf(a, b))
        SystemFileSystem.unzipFrom(zip, dest)

        assertEquals("A", readFile(Path(dest, "a.txt")))
        assertEquals("B", readFile(Path(dest, "b.txt")))
    }

    @Test
    fun roundTripWithPassword() = runTest {
        val src = path("secret.txt")
        val zip = path("out", "archive.zip")
        val dest = path("extracted")
        val password = "swordfish"
        val payload = "encrypted payload"

        writeFile(src, payload)

        SystemFileSystem.zipTo(zip, listOf(src), password = password)
        SystemFileSystem.unzipFrom(zip, dest, password = password)

        assertEquals(payload, readFile(Path(dest, "secret.txt")))
    }

    @Test
    fun roundTripBinary() = runTest {
        val src = path("data.bin")
        val zip = path("out", "archive.zip")
        val dest = path("extracted")
        val bytes = ByteArray(20_000) { (it * 37 % 256).toByte() }

        src.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(src).buffered().use { it.write(bytes) }

        SystemFileSystem.zipTo(zip, listOf(src))
        SystemFileSystem.unzipFrom(zip, dest)

        val extracted = SystemFileSystem.source(Path(dest, "data.bin")).buffered().use { it.readByteArray() }
        assertContentEquals(bytes, extracted)
    }

    @Test
    fun zipToOverwritesExistingTarget() = runTest {
        val src = path("a.txt")
        val zip = path("out", "archive.zip")
        writeFile(src, "first")
        SystemFileSystem.zipTo(zip, listOf(src))

        writeFile(src, "second")
        SystemFileSystem.zipTo(zip, listOf(src))

        val dest = path("extracted")
        SystemFileSystem.unzipFrom(zip, dest)
        assertEquals("second", readFile(Path(dest, "a.txt")))
    }

    @Test
    fun unzipRejectsParentTraversal() = runTest {
        val zipPath = path("out", "evil.zip")
        writeMaliciousArchive(zipPath, "../escaped.txt")

        assertFailsWith<IllegalArgumentException> {
            SystemFileSystem.unzipFrom(zipPath, path("extracted"))
        }
    }

    @Test
    fun unzipRejectsAbsolutePath() = runTest {
        val zipPath = path("out", "evil.zip")
        writeMaliciousArchive(zipPath, "/etc/passwd")

        assertFailsWith<IllegalArgumentException> {
            SystemFileSystem.unzipFrom(zipPath, path("extracted"))
        }
    }

    @Test
    fun unzipRejectsDriveLetter() = runTest {
        val zipPath = path("out", "evil.zip")
        writeMaliciousArchive(zipPath, "C:/windows/evil.txt")

        assertFailsWith<IllegalArgumentException> {
            SystemFileSystem.unzipFrom(zipPath, path("extracted"))
        }
    }

    @Test
    fun unzipRejectsControlChar() = runTest {
        val zipPath = path("out", "evil.zip")
        writeMaliciousArchive(zipPath, "evil.txt")

        assertFailsWith<IllegalArgumentException> {
            SystemFileSystem.unzipFrom(zipPath, path("extracted"))
        }
    }

    @Test
    fun zipToCreatesParentDirectories() = runTest {
        val src = path("hello.txt")
        val zip = path("deep", "nested", "path", "archive.zip")

        writeFile(src, "hi")

        SystemFileSystem.zipTo(zip, listOf(src))

        assertTrue(SystemFileSystem.exists(zip))
    }

    @Test
    fun zipToFailsOnMissingSource() = runTest {
        assertFails {
            SystemFileSystem.zipTo(path("out", "archive.zip"), listOf(path("nope.txt")))
        }
    }

    @Test
    fun dispatcherIsHonored() = runTest {
        val src = path("a.txt")
        val zip = path("out", "archive.zip")
        writeFile(src, "hi")

        val counter = CountingDispatcher()
        SystemFileSystem.zipTo(zip, listOf(src), dispatcher = counter)

        assertTrue(counter.dispatched > 0, "Custom dispatcher should have been used")
    }

    @Test
    fun cancellationAbortsZip() = runTest {
        val srcDir = path("big")
        SystemFileSystem.createDirectories(srcDir)
        repeat(5_000) { i ->
            writeFile(Path(srcDir, "file-$i.txt"), "payload-$i".repeat(20))
        }
        val zip = path("out", "archive.zip")

        assertFails {
            withTimeout(10.milliseconds) {
                SystemFileSystem.zipTo(zip, listOf(srcDir))
            }
        }
    }

    private fun writeMaliciousArchive(target: Path, entryName: String) {
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        val malicious = no.synth.kmpzip.io.ByteArrayOutputStream().also { baos ->
            no.synth.kmpzip.zip.ZipOutputStream(baos).use { z ->
                z.putNextEntry(no.synth.kmpzip.zip.ZipEntry(entryName))
                z.write("pwned".encodeToByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        SystemFileSystem.sink(target).buffered().use { it.write(malicious) }
    }

    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched: Int = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            Dispatchers.Default.dispatch(context, block)
        }
    }
}
