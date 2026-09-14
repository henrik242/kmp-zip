package no.synth.kmpzip.zip

import no.synth.kmpzip.gzip.GzipException
import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.IOException
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the exception hierarchy the public API advertises, through real failing calls:
 * a ZIP failure is catchable as `ZipException` and as `IOException`, a GZIP failure as
 * `GzipException` and as `IOException`. The `IOException` cases are the ones that keep
 * `catch (IOException)` working on the JVM; they fail if a supertype is ever dropped.
 */
class ExceptionHierarchyTest {

    private fun failZip() = ZipFile(ByteArray(100) { 0 })

    private fun failGzip() =
        GzipInputStream(ByteArrayInputStream(ByteArray(16) { 7 })).use { it.readBytes() }

    @Test
    fun zipFailureIsZipException() {
        assertFailsWith<ZipException> { failZip() }
    }

    @Test
    fun zipFailureIsIOException() {
        assertFailsWith<IOException> { failZip() }
    }

    @Test
    fun gzipFailureIsGzipException() {
        assertFailsWith<GzipException> { failGzip() }
    }

    @Test
    fun gzipFailureIsIOException() {
        assertFailsWith<IOException> { failGzip() }
    }
}
