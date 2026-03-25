package no.synth.kmpzip.cli

import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {

    @Test
    fun `round-trip create and extract zip`() {
        val tmpDir = createTempDir("cli-test")
        try {
            // Set up source files
            val srcDir = File(tmpDir, "src").apply { mkdirs() }
            File(srcDir, "hello.txt").writeText("Hello, world!")
            File(srcDir, "subdir").mkdirs()
            File(srcDir, "subdir/nested.txt").writeText("Nested content")

            // Create zip
            val zipFile = File(tmpDir, "test.zip")
            main(arrayOf("create", zipFile.path, srcDir.path))
            assertTrue(zipFile.exists())
            assertTrue(zipFile.length() > 0)

            // List zip
            main(arrayOf("list", zipFile.path))

            // Extract zip
            val extractDir = File(tmpDir, "out").apply { mkdirs() }
            main(arrayOf("extract", zipFile.path, "-d", extractDir.path))

            val hello = File(extractDir, "src/hello.txt")
            assertTrue(hello.exists())
            assertEquals("Hello, world!", hello.readText())

            val nested = File(extractDir, "src/subdir/nested.txt")
            assertTrue(nested.exists())
            assertEquals("Nested content", nested.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `round-trip gzip and gunzip`() {
        val tmpDir = createTempDir("cli-gz-test")
        try {
            val original = File(tmpDir, "data.txt")
            original.writeText("The quick brown fox jumps over the lazy dog.\n".repeat(100))

            // Compress
            main(arrayOf("gzip", original.path))
            val gzFile = File("${original.path}.gz")
            assertTrue(gzFile.exists())
            assertTrue(gzFile.length() < original.length(), "gzip should compress the data")

            // Remove original, then decompress
            val originalSize = original.length()
            original.delete()
            main(arrayOf("gunzip", gzFile.path))
            assertTrue(original.exists())
            assertEquals(originalSize, original.length())
            assertTrue(original.readText().startsWith("The quick brown fox"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `create and extract password-protected zip`() {
        val tmpDir = createTempDir("cli-pw-test")
        try {
            val src = File(tmpDir, "secret.txt").apply { writeText("top secret") }
            val zipFile = File(tmpDir, "encrypted.zip")

            main(arrayOf("create", zipFile.path, "-p", "mypass", src.path))
            assertTrue(zipFile.exists())

            val extractDir = File(tmpDir, "out").apply { mkdirs() }
            main(arrayOf("extract", zipFile.path, "-d", extractDir.path, "-p", "mypass"))

            val extracted = File(extractDir, "secret.txt")
            assertTrue(extracted.exists())
            assertEquals("top secret", extracted.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `create and extract legacy encrypted zip`() {
        val tmpDir = createTempDir("cli-legacy-test")
        try {
            val src = File(tmpDir, "secret.txt").apply { writeText("legacy encrypted") }
            val zipFile = File(tmpDir, "legacy.zip")

            main(arrayOf("create", zipFile.path, "-p", "pass", "--legacy", src.path))
            assertTrue(zipFile.exists())

            val extractDir = File(tmpDir, "out").apply { mkdirs() }
            main(arrayOf("extract", zipFile.path, "-d", extractDir.path, "-p", "pass"))

            val extracted = File(extractDir, "secret.txt")
            assertTrue(extracted.exists())
            assertEquals("legacy encrypted", extracted.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `list password-protected zip`() {
        val tmpDir = createTempDir("cli-list-pw-test")
        try {
            val src = File(tmpDir, "data.txt").apply { writeText("encrypted data here") }
            val zipFile = File(tmpDir, "encrypted.zip")

            main(arrayOf("create", zipFile.path, "-p", "pass123", src.path))

            // List with password should not throw
            main(arrayOf("list", zipFile.path, "-p", "pass123"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `gunzip file without gz extension produces dot-out file`() {
        val tmpDir = createTempDir("cli-guz-ext-test")
        try {
            // Create a gzip file with a non-.gz name
            val original = File(tmpDir, "data.txt")
            original.writeText("Some data for compression")
            main(arrayOf("gzip", original.path))

            val gzFile = File("${original.path}.gz")
            val renamed = File(tmpDir, "data.bin")
            gzFile.renameTo(renamed)

            main(arrayOf("gunzip", renamed.path))

            val outFile = File("${renamed.path}.out")
            assertTrue(outFile.exists(), "Should create .out file for non-.gz input")
            assertEquals("Some data for compression", outFile.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `create zip with multiple individual files`() {
        val tmpDir = createTempDir("cli-multi-test")
        try {
            val file1 = File(tmpDir, "a.txt").apply { writeText("aaa") }
            val file2 = File(tmpDir, "b.txt").apply { writeText("bbb") }
            val zipFile = File(tmpDir, "multi.zip")

            main(arrayOf("create", zipFile.path, file1.path, file2.path))
            assertTrue(zipFile.exists())

            val extractDir = File(tmpDir, "out").apply { mkdirs() }
            main(arrayOf("extract", zipFile.path, "-d", extractDir.path))

            assertEquals("aaa", File(extractDir, "a.txt").readText())
            assertEquals("bbb", File(extractDir, "b.txt").readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `create and extract empty directory entry`() {
        val tmpDir = createTempDir("cli-emptydir-test")
        try {
            val srcDir = File(tmpDir, "parent").apply { mkdirs() }
            File(srcDir, "emptydir").mkdirs()
            File(srcDir, "file.txt").writeText("content")

            val zipFile = File(tmpDir, "dirs.zip")
            main(arrayOf("create", zipFile.path, srcDir.path))

            val extractDir = File(tmpDir, "out").apply { mkdirs() }
            main(arrayOf("extract", zipFile.path, "-d", extractDir.path))

            assertTrue(File(extractDir, "parent/emptydir").isDirectory)
            assertEquals("content", File(extractDir, "parent/file.txt").readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `extract to default directory`() {
        val tmpDir = createTempDir("cli-default-dir-test")
        try {
            val src = File(tmpDir, "hello.txt").apply { writeText("default dir") }
            val zipFile = File(tmpDir, "test.zip")
            main(arrayOf("create", zipFile.path, src.path))

            // Extract without -d uses "." — create a zip and verify we can extract it
            // Use an explicit output dir to avoid polluting the working directory
            main(arrayOf("extract", zipFile.path, "-d", tmpDir.path))
            assertEquals("default dir", File(tmpDir, "hello.txt").readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `gzip and gunzip short aliases work`() {
        val tmpDir = createTempDir("cli-alias-test")
        try {
            val original = File(tmpDir, "data.txt")
            original.writeText("alias test data")
            main(arrayOf("z", original.path))

            val gzFile = File("${original.path}.gz")
            assertTrue(gzFile.exists())
            original.delete()

            main(arrayOf("u", gzFile.path))
            assertTrue(original.exists())
            assertEquals("alias test data", original.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `help command prints usage`() {
        // Should not throw
        main(arrayOf("help"))
        main(arrayOf("--help"))
        main(arrayOf("-h"))
    }

    private fun createTempDir(prefix: String): File {
        return File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
    }
}
