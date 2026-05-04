package no.synth.kmpzip.cli

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.gzip.GzipOutputStream
import no.synth.kmpzip.kotlinx.asInputStream
import no.synth.kmpzip.kotlinx.asOutputStream
import no.synth.kmpzip.zip.ZipEncryption
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream

private const val BUFFER_SIZE = 8192

fun main(args: Array<String>) {
    val code = runCli(args)
    if (code != 0) exitProcess(code)
}

internal fun runCli(args: Array<String>): Int {
    val filtered = args.filter { it.isNotEmpty() }
    val command = filtered.firstOrNull()
    if (command == null || command == "--cwd") {
        printUsage()
        return 0
    }

    val remainingArgs = filtered.drop(1)

    return try {
        when (command) {
            "list", "l" -> { list(remainingArgs); 0 }
            "zip", "c" -> { zip(remainingArgs); 0 }
            "unzip", "x" -> { unzip(remainingArgs); 0 }
            "gzip", "z" -> { gzip(remainingArgs); 0 }
            "gunzip", "u" -> { gunzip(remainingArgs); 0 }
            "help", "-h", "--help" -> { printUsage(); 0 }
            else -> {
                printErr("Unknown command: $command")
                println()
                printUsage()
                1
            }
        }
    } catch (e: Exception) {
        printErr("Error: ${e.message ?: e::class.simpleName ?: "unknown"}")
        1
    }
}

private fun printUsage() {
    val usage = """
        kmpzip — ZIP/GZIP command-line tool powered by kmp-zip

        Usage: kmpzip <command> [options] [args]

        Commands:
          list,    l   <file.zip>           List ZIP contents
          zip,     c   <file.zip> <files..> Create ZIP from files
          unzip,   x   <file.zip>           Extract ZIP contents
          gzip,    z   <file>               GZIP compress a file
          gunzip,  u   <file.gz>            GZIP decompress a file
          help                              Show this help

        Options:
          -p <password>   Password for encrypted ZIP files
          -d <dir>        Output directory for extraction (default: current dir)
          --legacy        Use legacy ZipCrypto encryption instead of AES (zip only)
    """.trimIndent()
    println(usage)
}

private class CliArgs(args: List<String>) {
    var password: String? = null
    var outputDir: String? = null
    var legacy: Boolean = false
    var cwd: String? = currentWorkingDirectory()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-p", "--password" -> {
                    require(i + 1 < args.size) { "Missing value for ${args[i]}" }
                    password = args[++i]
                }
                "-d", "--dir" -> {
                    require(i + 1 < args.size) { "Missing value for ${args[i]}" }
                    outputDir = args[++i]
                }
                "--legacy" -> legacy = true
                "--cwd" -> {
                    require(i + 1 < args.size) { "Missing value for ${args[i]}" }
                    cwd = args[++i]
                }
                else -> positional.add(args[i])
            }
            i++
        }
    }

    /** Resolves a path relative to the caller's working directory. */
    fun resolve(path: String): Path {
        val p = Path(path)
        val base = cwd
        if (p.isAbsolute || base == null) return p
        return Path(base, path)
    }
}

private fun fileSize(path: Path): Long = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
private fun isDirectory(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isDirectory == true
private fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

private fun padRight(s: String, w: Int): String = if (s.length >= w) s else s + " ".repeat(w - s.length)
private fun padLeft(s: String, w: Int): String = if (s.length >= w) s else " ".repeat(w - s.length) + s

private val DRIVE_LETTER_REGEX = Regex("^[A-Za-z]:")

/**
 * Builds a target Path under [outputDir] that is guaranteed not to escape it.
 * Rejects absolute paths, drive-letter prefixes, parent-dir traversal, control chars
 * (including embedded NULs), and trims redundant `.` segments. Tab is allowed in
 * names because some legitimate ZIPs contain it.
 */
private fun safeEntryPath(outputDir: Path, rawName: String): Path {
    require(rawName.isNotEmpty()) { "Empty entry name not allowed" }
    require(rawName.none { it.code < 0x20 && it != '\t' }) {
        "Entry name contains control character: ${rawName.encodeToByteArray().joinToString(" ") { it.toUByte().toString(16) }}"
    }
    require(!rawName.startsWith("/") && !rawName.startsWith("\\")) {
        "Absolute entry path not allowed: $rawName"
    }
    require(!DRIVE_LETTER_REGEX.containsMatchIn(rawName)) {
        "Drive-letter entry path not allowed: $rawName"
    }
    val segments = rawName.split('/', '\\').filter { it.isNotEmpty() && it != "." }
    require(segments.none { it == ".." }) { "Entry path escapes target dir: $rawName" }
    return segments.fold(outputDir) { acc, seg -> Path(acc, seg) }
}

// -- list --

private fun list(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmpzip list <file.zip> [-p password]" }

    val file = cli.resolve(cli.positional[0])
    require(exists(file)) { "File not found: $file" }

    val source = SystemFileSystem.source(file).buffered()
    val password = cli.password
    val zis = if (password != null) {
        ZipInputStream(source.asInputStream(), password.encodeToByteArray())
    } else {
        ZipInputStream(source.asInputStream())
    }

    zis.use {
        var headerPrinted = false
        while (true) {
            val entry = it.nextEntry ?: break
            if (!headerPrinted) {
                println("${padRight("Method", 8)}  ${padRight("Size", 12)}  ${padRight("Compressed", 12)}  Name")
                println("-".repeat(60))
                headerPrinted = true
            }
            val drain = ByteArray(BUFFER_SIZE)
            while (it.read(drain, 0, drain.size) != -1) { /* discard */ }

            val method = when (entry.method) {
                0 -> "STORED"
                8 -> "DEFLATED"
                else -> "m:${entry.method}"
            }
            val size = if (entry.size >= 0) entry.size.toString() else "?"
            val compressedSize = if (entry.compressedSize >= 0) entry.compressedSize.toString() else "?"
            println("${padRight(method, 8)}  ${padLeft(size, 12)}  ${padLeft(compressedSize, 12)}  ${entry.name}")
        }
    }
}

// -- unzip --

private fun unzip(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmpzip unzip <file.zip> [-d dir] [-p password]" }

    val file = cli.resolve(cli.positional[0])
    require(exists(file)) { "File not found: $file" }

    val outputDir = cli.resolve(cli.outputDir ?: ".")
    SystemFileSystem.createDirectories(outputDir)

    val source = SystemFileSystem.source(file).buffered()
    val password = cli.password
    val zis = if (password != null) {
        ZipInputStream(source.asInputStream(), password.encodeToByteArray())
    } else {
        ZipInputStream(source.asInputStream())
    }

    var skipped = 0
    zis.use {
        while (true) {
            val entry = it.nextEntry ?: break
            try {
                val target = safeEntryPath(outputDir, entry.name)

                if (entry.isDirectory) {
                    SystemFileSystem.createDirectories(target)
                    println("  created: ${entry.name}")
                } else {
                    target.parent?.let { p -> SystemFileSystem.createDirectories(p) }
                    SystemFileSystem.sink(target).buffered().asOutputStream().use { fos ->
                        val buf = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = it.read(buf, 0, buf.size)
                            if (n == -1) break
                            fos.write(buf, 0, n)
                        }
                    }
                    println("extracted: ${entry.name} (${fileSize(target)} bytes)")
                }
            } catch (e: IllegalArgumentException) {
                // Per-entry isolation: skip malformed/dangerous names but continue extracting
                // the rest of the archive. The trailing `if (skipped > 0) error(...)` ensures
                // a non-zero exit code is still propagated.
                printErr("Skipping bad entry '${entry.name}': ${e.message}")
                skipped++
            }
        }
    }
    if (skipped > 0) {
        error("$skipped entr${if (skipped == 1) "y" else "ies"} skipped due to errors")
    }
}

// -- zip --

private fun zip(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.size >= 2) {
        "Usage: kmpzip zip <file.zip> [-p password] <files..>"
    }

    val zipFile = cli.resolve(cli.positional[0])
    val inputFiles = cli.positional.drop(1).map { path ->
        cli.resolve(path).also { f ->
            require(exists(f)) { "File not found: $f" }
        }
    }

    val sink = SystemFileSystem.sink(zipFile).buffered()
    val password = cli.password
    val encryption = if (cli.legacy) ZipEncryption.LEGACY else ZipEncryption.AES
    val zos = if (password != null) {
        ZipOutputStream(sink.asOutputStream(), password.encodeToByteArray(), encryption)
    } else {
        ZipOutputStream(sink.asOutputStream())
    }

    zos.use {
        for (inputFile in inputFiles) {
            addToZip(it, inputFile, "")
        }
    }

    println("Created $zipFile (${fileSize(zipFile)} bytes)")
}

private fun addToZip(zos: ZipOutputStream, file: Path, prefix: String) {
    val entryName = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"

    if (isDirectory(file)) {
        zos.putNextEntry(ZipEntry("$entryName/"))
        zos.closeEntry()
        println("  added: $entryName/")

        SystemFileSystem.list(file).sortedBy { it.name }.forEach { child ->
            addToZip(zos, child, entryName)
        }
    } else {
        zos.putNextEntry(ZipEntry(entryName))
        SystemFileSystem.source(file).buffered().asInputStream().use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = fis.read(buf, 0, buf.size)
                if (n == -1) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
        println("  added: $entryName (${fileSize(file)} bytes)")
    }
}

// -- gzip --

private fun gzip(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmpzip gzip <file>" }

    val inputFile = cli.resolve(cli.positional[0])
    require(exists(inputFile)) { "File not found: $inputFile" }
    require(!inputFile.name.endsWith(".gz")) {
        "$inputFile already has a .gz suffix — refusing to double-compress"
    }

    val outputFile = Path("$inputFile.gz")
    require(!exists(outputFile)) { "$outputFile already exists — refusing to overwrite" }

    SystemFileSystem.source(inputFile).buffered().asInputStream().use { fis ->
        GzipOutputStream(SystemFileSystem.sink(outputFile).buffered().asOutputStream()).use { gzos ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = fis.read(buf, 0, buf.size)
                if (n == -1) break
                gzos.write(buf, 0, n)
            }
        }
    }

    println("$inputFile -> $outputFile (${fileSize(inputFile)} -> ${fileSize(outputFile)} bytes)")
}

// -- gunzip --

private fun gunzip(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmpzip gunzip <file.gz>" }

    val inputFile = cli.resolve(cli.positional[0])
    require(exists(inputFile)) { "File not found: $inputFile" }

    val inputPath = inputFile.toString()
    val outputName = if (inputPath.endsWith(".gz")) {
        inputPath.removeSuffix(".gz")
    } else {
        "$inputPath.out"
    }
    val outputFile = Path(outputName)
    require(!exists(outputFile)) { "$outputFile already exists — refusing to overwrite" }

    GzipInputStream(SystemFileSystem.source(inputFile).buffered().asInputStream()).use { gzis ->
        SystemFileSystem.sink(outputFile).buffered().asOutputStream().use { fos ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = gzis.read(buf, 0, buf.size)
                if (n == -1) break
                fos.write(buf, 0, n)
            }
        }
    }

    println("$inputFile -> $outputFile (${fileSize(inputFile)} -> ${fileSize(outputFile)} bytes)")
}
