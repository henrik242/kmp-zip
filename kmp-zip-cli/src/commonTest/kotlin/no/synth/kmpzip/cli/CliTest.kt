package no.synth.kmpzip.cli

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import no.synth.kmpzip.kotlinx.asInputStream
import no.synth.kmpzip.kotlinx.asOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {

    @Test
    fun roundTripCreateAndExtractZip() {
        val tmpDir = createTempDir("cli-test")
        try {
            val srcDir = Path(tmpDir, "src").also { SystemFileSystem.createDirectories(it) }
            writeText(Path(srcDir, "hello.txt"), "Hello, world!")
            SystemFileSystem.createDirectories(Path(srcDir, "subdir"))
            writeText(Path(srcDir, "subdir", "nested.txt"), "Nested content")

            val zipFile = Path(tmpDir, "test.zip")
            runCli(arrayOf("zip", zipFile.toString(), srcDir.toString()))
            assertTrue(SystemFileSystem.exists(zipFile))
            assertTrue(fileSize(zipFile) > 0)

            runCli(arrayOf("list", zipFile.toString()))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString()))

            val hello = Path(extractDir, "src", "hello.txt")
            assertTrue(SystemFileSystem.exists(hello))
            assertEquals("Hello, world!", readText(hello))

            val nested = Path(extractDir, "src", "subdir", "nested.txt")
            assertTrue(SystemFileSystem.exists(nested))
            assertEquals("Nested content", readText(nested))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun roundTripGzipAndGunzip() {
        val tmpDir = createTempDir("cli-gz-test")
        try {
            val original = Path(tmpDir, "data.txt")
            writeText(original, "The quick brown fox jumps over the lazy dog.\n".repeat(100))

            runCli(arrayOf("gzip", original.toString()))
            val gzFile = Path("${original}.gz")
            assertTrue(SystemFileSystem.exists(gzFile))
            assertTrue(fileSize(gzFile) < fileSize(original), "gzip should compress the data")

            val originalSize = fileSize(original)
            SystemFileSystem.delete(original)
            runCli(arrayOf("gunzip", gzFile.toString()))
            assertTrue(SystemFileSystem.exists(original))
            assertEquals(originalSize, fileSize(original))
            assertTrue(readText(original).startsWith("The quick brown fox"))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun createAndExtractPasswordProtectedZip() {
        val tmpDir = createTempDir("cli-pw-test")
        try {
            val src = Path(tmpDir, "secret.txt").also { writeText(it, "top secret") }
            val zipFile = Path(tmpDir, "encrypted.zip")

            runCli(arrayOf("zip", zipFile.toString(), "-p", "mypass", src.toString()))
            assertTrue(SystemFileSystem.exists(zipFile))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString(), "-p", "mypass"))

            val extracted = Path(extractDir, "secret.txt")
            assertTrue(SystemFileSystem.exists(extracted))
            assertEquals("top secret", readText(extracted))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun createAndExtractLegacyEncryptedZip() {
        val tmpDir = createTempDir("cli-legacy-test")
        try {
            val src = Path(tmpDir, "secret.txt").also { writeText(it, "legacy encrypted") }
            val zipFile = Path(tmpDir, "legacy.zip")

            runCli(arrayOf("zip", zipFile.toString(), "-p", "pass", "--legacy", src.toString()))
            assertTrue(SystemFileSystem.exists(zipFile))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString(), "-p", "pass"))

            val extracted = Path(extractDir, "secret.txt")
            assertTrue(SystemFileSystem.exists(extracted))
            assertEquals("legacy encrypted", readText(extracted))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun listPasswordProtectedZip() {
        val tmpDir = createTempDir("cli-list-pw-test")
        try {
            val src = Path(tmpDir, "data.txt").also { writeText(it, "encrypted data here") }
            val zipFile = Path(tmpDir, "encrypted.zip")

            runCli(arrayOf("zip", zipFile.toString(), "-p", "pass123", src.toString()))
            runCli(arrayOf("list", zipFile.toString(), "-p", "pass123"))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun gunzipFileWithoutGzExtensionProducesDotOutFile() {
        val tmpDir = createTempDir("cli-guz-ext-test")
        try {
            val original = Path(tmpDir, "data.txt")
            writeText(original, "Some data for compression")
            runCli(arrayOf("gzip", original.toString()))

            val gzFile = Path("${original}.gz")
            val renamed = Path(tmpDir, "data.bin")
            SystemFileSystem.atomicMove(gzFile, renamed)

            runCli(arrayOf("gunzip", renamed.toString()))

            val outFile = Path("${renamed}.out")
            assertTrue(SystemFileSystem.exists(outFile), "Should create .out file for non-.gz input")
            assertEquals("Some data for compression", readText(outFile))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun createZipWithMultipleIndividualFiles() {
        val tmpDir = createTempDir("cli-multi-test")
        try {
            val file1 = Path(tmpDir, "a.txt").also { writeText(it, "aaa") }
            val file2 = Path(tmpDir, "b.txt").also { writeText(it, "bbb") }
            val zipFile = Path(tmpDir, "multi.zip")

            runCli(arrayOf("zip", zipFile.toString(), file1.toString(), file2.toString()))
            assertTrue(SystemFileSystem.exists(zipFile))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString()))

            assertEquals("aaa", readText(Path(extractDir, "a.txt")))
            assertEquals("bbb", readText(Path(extractDir, "b.txt")))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun createAndExtractEmptyDirectoryEntry() {
        val tmpDir = createTempDir("cli-emptydir-test")
        try {
            val srcDir = Path(tmpDir, "parent").also { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.createDirectories(Path(srcDir, "emptydir"))
            writeText(Path(srcDir, "file.txt"), "content")

            val zipFile = Path(tmpDir, "dirs.zip")
            runCli(arrayOf("zip", zipFile.toString(), srcDir.toString()))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString()))

            assertTrue(SystemFileSystem.metadataOrNull(Path(extractDir, "parent", "emptydir"))?.isDirectory == true)
            assertEquals("content", readText(Path(extractDir, "parent", "file.txt")))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun extractToExplicitDirectory() {
        val tmpDir = createTempDir("cli-default-dir-test")
        try {
            val src = Path(tmpDir, "hello.txt").also { writeText(it, "default dir") }
            val zipFile = Path(tmpDir, "test.zip")
            runCli(arrayOf("zip", zipFile.toString(), src.toString()))

            runCli(arrayOf("unzip", zipFile.toString(), "-d", tmpDir.toString()))
            assertEquals("default dir", readText(Path(tmpDir, "hello.txt")))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun gzipAndGunzipShortAliasesWork() {
        val tmpDir = createTempDir("cli-alias-test")
        try {
            val original = Path(tmpDir, "data.txt")
            writeText(original, "alias test data")
            runCli(arrayOf("z", original.toString()))

            val gzFile = Path("${original}.gz")
            assertTrue(SystemFileSystem.exists(gzFile))
            SystemFileSystem.delete(original)

            runCli(arrayOf("u", gzFile.toString()))
            assertTrue(SystemFileSystem.exists(original))
            assertEquals("alias test data", readText(original))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun helpCommandPrintsUsage() {
        runCli(arrayOf("help"))
        runCli(arrayOf("--help"))
        runCli(arrayOf("-h"))
    }

    @Test
    fun extractRejectsZipSlipParentTraversal() {
        assertExtractDoesNotEscape("../escape.txt")
    }

    @Test
    fun extractRejectsZipSlipMidPathTraversal() {
        assertExtractDoesNotEscape("foo/../../escape.txt")
    }

    @Test
    fun extractRejectsAbsoluteEntryName() {
        assertExtractDoesNotEscape("/abs.txt")
    }

    @Test
    fun extractRejectsBackslashAbsoluteEntryName() {
        assertExtractDoesNotEscape("\\abs.txt")
    }

    @Test
    fun extractRejectsDriveLetterEntryName() {
        assertExtractDoesNotEscape("C:/escape.txt")
    }

    @Test
    fun extractRejectsNullByteInEntryName() {
        assertExtractDoesNotEscape("safe.txt ../escape.txt")
    }

    @Test
    fun extractAllowsDotPrefixedFiles() {
        // .foo and ..foo are legal filenames (only segments equal to ".." are dangerous).
        val tmpDir = createTempDir("cli-dotfile-test")
        try {
            val zipFile = Path(tmpDir, "dot.zip")
            buildZip(zipFile, mapOf(".hidden" to "hidden", "..twodot" to "twodot"))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString()))

            assertEquals("hidden", readText(Path(extractDir, ".hidden")))
            assertEquals("twodot", readText(Path(extractDir, "..twodot")))
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun createdZipUsesForwardSlashEntrySeparators() {
        val tmpDir = createTempDir("cli-sep-test")
        try {
            val srcDir = Path(tmpDir, "src").also { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.createDirectories(Path(srcDir, "sub"))
            writeText(Path(srcDir, "sub", "deep.txt"), "deep")

            val zipFile = Path(tmpDir, "test.zip")
            runCli(arrayOf("zip", zipFile.toString(), srcDir.toString()))

            val entryNames = mutableListOf<String>()
            ZipInputStream(SystemFileSystem.source(zipFile).buffered().asInputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryNames.add(entry.name)
                }
            }
            assertTrue(entryNames.isNotEmpty(), "zip should have entries")
            for (name in entryNames) {
                assertFalse(name.contains('\\'), "entry name contains backslash: $name")
            }
            assertTrue(entryNames.any { it == "src/sub/deep.txt" || it == "src/sub/deep.txt/" || it.endsWith("/deep.txt") }, "expected a deep entry, got: $entryNames")
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun gzipRefusesDoubleCompression() {
        val tmpDir = createTempDir("cli-gz-double-test")
        try {
            val original = Path(tmpDir, "data.txt")
            writeText(original, "first compression")
            runCli(arrayOf("gzip", original.toString()))
            val gz = Path("${original}.gz")
            assertTrue(SystemFileSystem.exists(gz))

            // Second gzip on the .gz file should refuse — runCli catches the error,
            // prints it, and returns 1.
            assertEquals(1, runCli(arrayOf("gzip", gz.toString())))
            assertFalse(SystemFileSystem.exists(Path("${gz}.gz")), "should not produce .gz.gz")
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    @Test
    fun unknownCommandReturnsNonZero() {
        assertEquals(1, runCli(arrayOf("frobnicate")))
    }

    /**
     * Builds a malicious ZIP, runs unzip, and asserts:
     * 1. No file was written outside the intended extraction directory anywhere within
     *    the temp dir (the security guarantee).
     * 2. runCli returned a non-zero exit code so shell composition surfaces the error.
     */
    private fun assertExtractDoesNotEscape(maliciousEntryName: String) {
        val tmpDir = createTempDir("cli-zipslip-test")
        try {
            val zipFile = Path(tmpDir, "malicious.zip")
            buildZip(zipFile, mapOf(maliciousEntryName to "should not escape"))

            val extractDir = Path(tmpDir, "out").also { SystemFileSystem.createDirectories(it) }
            val code = runCli(arrayOf("unzip", zipFile.toString(), "-d", extractDir.toString()))

            assertFalse(
                anyFileContainsContent(tmpDir, "should not escape", excludeZip = zipFile),
                "Zip-slip escape: malicious payload found on disk for entry '$maliciousEntryName'"
            )
            assertEquals(1, code, "unzip should exit non-zero when entries are skipped")
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    private fun buildZip(zipFile: Path, entries: Map<String, String>) {
        ZipOutputStream(SystemFileSystem.sink(zipFile).buffered().asOutputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.encodeToByteArray(), 0, content.length)
                zos.closeEntry()
            }
        }
    }

    private fun anyFileContainsContent(root: Path, content: String, excludeZip: Path): Boolean {
        if (!SystemFileSystem.exists(root)) return false
        val md = SystemFileSystem.metadataOrNull(root) ?: return false
        if (md.isDirectory) {
            return SystemFileSystem.list(root).any { anyFileContainsContent(it, content, excludeZip) }
        }
        if (root.toString() == excludeZip.toString()) return false
        return runCatching { readText(root) }.getOrNull() == content
    }

    // -- helpers --

    private fun createTempDir(prefix: String): Path {
        val name = "$prefix-${Random.nextLong().toString(36).removePrefix("-")}"
        return Path(SystemTemporaryDirectory, name).also { SystemFileSystem.createDirectories(it) }
    }

    private fun writeText(path: Path, content: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    private fun readText(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun fileSize(path: Path): Long =
        SystemFileSystem.metadataOrNull(path)?.size ?: 0L

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path)
    }
}
