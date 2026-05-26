package no.synth.kmpzip.okio

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class FileSystemHelpersCancellationTest {

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

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10.milliseconds) {
                fs.zipTo(zip, listOf(src))
            }
        }
    }
}
