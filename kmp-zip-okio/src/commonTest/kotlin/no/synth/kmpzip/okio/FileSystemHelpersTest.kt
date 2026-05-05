package no.synth.kmpzip.okio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileSystemHelpersTest {

    @Test
    fun roundTripSingleFile() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/hello.txt".toPath()
        val zip = "/out/archive.zip".toPath()
        val dest = "/extracted".toPath()

        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8("Hello, FS!") }

        fs.zipTo(zip, listOf(src))
        fs.unzipFrom(zip, dest)

        assertEquals("Hello, FS!", fs.read(dest / "hello.txt") { readUtf8() })
    }

    @Test
    fun roundTripDirectory() = runTest {
        val fs = FakeFileSystem()
        val srcDir = "/work/data".toPath()
        val zip = "/out/archive.zip".toPath()
        val dest = "/extracted".toPath()

        fs.createDirectories(srcDir / "nested")
        fs.write(srcDir / "a.txt") { writeUtf8("alpha") }
        fs.write(srcDir / "b.txt") { writeUtf8("beta") }
        fs.write(srcDir / "nested" / "c.txt") { writeUtf8("gamma") }

        fs.zipTo(zip, listOf(srcDir))
        fs.unzipFrom(zip, dest)

        assertEquals("alpha", fs.read(dest / "data" / "a.txt") { readUtf8() })
        assertEquals("beta", fs.read(dest / "data" / "b.txt") { readUtf8() })
        assertEquals("gamma", fs.read(dest / "data" / "nested" / "c.txt") { readUtf8() })
    }

    @Test
    fun roundTripMultipleSources() = runTest {
        val fs = FakeFileSystem()
        val a = "/work/a.txt".toPath()
        val b = "/work/b.txt".toPath()
        val zip = "/out/archive.zip".toPath()
        val dest = "/extracted".toPath()

        fs.createDirectories(a.parent!!)
        fs.write(a) { writeUtf8("A") }
        fs.write(b) { writeUtf8("B") }

        fs.zipTo(zip, listOf(a, b))
        fs.unzipFrom(zip, dest)

        assertEquals("A", fs.read(dest / "a.txt") { readUtf8() })
        assertEquals("B", fs.read(dest / "b.txt") { readUtf8() })
    }

    @Test
    fun roundTripWithPassword() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/secret.txt".toPath()
        val zip = "/out/archive.zip".toPath()
        val dest = "/extracted".toPath()
        val password = "swordfish"
        val payload = "encrypted payload"

        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8(payload) }

        fs.zipTo(zip, listOf(src), password = password)
        fs.unzipFrom(zip, dest, password = password)

        assertEquals(payload, fs.read(dest / "secret.txt") { readUtf8() })
    }

    @Test
    fun roundTripBinary() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/data.bin".toPath()
        val zip = "/out/archive.zip".toPath()
        val dest = "/extracted".toPath()
        val bytes = ByteArray(20_000) { (it * 37 % 256).toByte() }

        fs.createDirectories(src.parent!!)
        fs.write(src) { write(bytes) }

        fs.zipTo(zip, listOf(src))
        fs.unzipFrom(zip, dest)

        val extracted = fs.read(dest / "data.bin") { readByteArray() }
        assertContentEquals(bytes, extracted)
    }

    @Test
    fun zipToOverwritesExistingTarget() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/a.txt".toPath()
        val zip = "/out/archive.zip".toPath()
        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8("first") }
        fs.zipTo(zip, listOf(src))

        fs.write(src) { writeUtf8("second") }
        fs.zipTo(zip, listOf(src))

        val dest = "/extracted".toPath()
        fs.unzipFrom(zip, dest)
        assertEquals("second", fs.read(dest / "a.txt") { readUtf8() })
    }

    @Test
    fun unzipRejectsParentTraversal() = runTest {
        val fs = malformedArchiveFs("../escaped.txt")
        assertFailsWith<IllegalArgumentException> {
            fs.unzipFrom("/out/evil.zip".toPath(), "/extracted".toPath())
        }
    }

    @Test
    fun unzipRejectsAbsolutePath() = runTest {
        val fs = malformedArchiveFs("/etc/passwd")
        assertFailsWith<IllegalArgumentException> {
            fs.unzipFrom("/out/evil.zip".toPath(), "/extracted".toPath())
        }
    }

    @Test
    fun unzipRejectsDriveLetter() = runTest {
        val fs = malformedArchiveFs("C:/windows/evil.txt")
        assertFailsWith<IllegalArgumentException> {
            fs.unzipFrom("/out/evil.zip".toPath(), "/extracted".toPath())
        }
    }

    @Test
    fun unzipRejectsControlChar() = runTest {
        val fs = malformedArchiveFs("evil.txt")
        assertFailsWith<IllegalArgumentException> {
            fs.unzipFrom("/out/evil.zip".toPath(), "/extracted".toPath())
        }
    }

    @Test
    fun zipToCreatesParentDirectories() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/hello.txt".toPath()
        val zip = "/deep/nested/path/archive.zip".toPath()

        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8("hi") }

        fs.zipTo(zip, listOf(src))

        assertTrue(fs.exists(zip))
    }

    @Test
    fun zipToFailsOnMissingSource() = runTest {
        val fs = FakeFileSystem()
        assertFails {
            fs.zipTo("/out/archive.zip".toPath(), listOf("/work/missing.txt".toPath()))
        }
    }

    @Test
    fun dispatcherIsHonored() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/a.txt".toPath()
        val zip = "/out/archive.zip".toPath()
        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8("hi") }

        val counter = CountingDispatcher()
        fs.zipTo(zip, listOf(src), dispatcher = counter)

        assertTrue(counter.dispatched > 0, "Custom dispatcher should have been used")
    }

    @Test
    fun cancellationAbortsZip() = runTest {
        val fs = FakeFileSystem()
        val src = "/work/big".toPath()
        fs.createDirectories(src)
        // Build a non-trivial workload so cancellation has time to land between
        // ensureActive() checks. 5K small files * ~10µs/entry > 50ms.
        repeat(5_000) { i ->
            fs.write(src / "file-$i.txt") { writeUtf8("payload-$i".repeat(20)) }
        }
        val zip = "/out/archive.zip".toPath()

        assertFails {
            withTimeout(10.milliseconds) {
                fs.zipTo(zip, listOf(src))
            }
        }
    }

    private fun malformedArchiveFs(entryName: String): FakeFileSystem {
        val fs = FakeFileSystem()
        val zipPath = "/out/evil.zip".toPath()
        fs.createDirectories(zipPath.parent!!)
        val malicious = no.synth.kmpzip.io.ByteArrayOutputStream().also { baos ->
            no.synth.kmpzip.zip.ZipOutputStream(baos).use { z ->
                z.putNextEntry(no.synth.kmpzip.zip.ZipEntry(entryName))
                z.write("pwned".encodeToByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        fs.write(zipPath) { write(malicious) }
        return fs
    }

    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched: Int = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            Dispatchers.Default.dispatch(context, block)
        }
    }
}
