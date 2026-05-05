package no.synth.kmpzip.cli

import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.gzip.GzipOutputStream
import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.kotlinx.asInputStream
import no.synth.kmpzip.kotlinx.asOutputStream
import no.synth.kmpzip.kotlinx.unzipFrom
import no.synth.kmpzip.kotlinx.zipTo
import no.synth.kmpzip.zip.ZipEncryption
import no.synth.kmpzip.zip.ZipInputStream

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
private fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

private fun padRight(s: String, w: Int): String = if (s.length >= w) s else s + " ".repeat(w - s.length)
private fun padLeft(s: String, w: Int): String = if (s.length >= w) s else " ".repeat(w - s.length) + s

// -- list --

private fun list(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmpzip list <file.zip> [-p password]" }

    val file = cli.resolve(cli.positional[0])
    require(exists(file)) { "File not found: $file" }

    val source = SystemFileSystem.source(file).buffered()
    val zis = ZipInputStream(source.asInputStream(), cli.password?.encodeToByteArray())

    zis.use {
        var headerPrinted = false
        while (true) {
            val entry = it.nextEntry ?: break
            if (!headerPrinted) {
                println("${padRight("Method", 8)}  ${padRight("Size", 12)}  ${padRight("Compressed", 12)}  Name")
                println("-".repeat(60))
                headerPrinted = true
            }
            it.drain()

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

    runBlocking {
        SystemFileSystem.unzipFrom(file, outputDir, password = cli.password)
    }
    println("Extracted $file -> $outputDir")
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

    val encryption = if (cli.legacy) ZipEncryption.LEGACY else ZipEncryption.AES

    runBlocking {
        SystemFileSystem.zipTo(zipFile, inputFiles, password = cli.password, encryption = encryption)
    }
    println("Created $zipFile (${fileSize(zipFile)} bytes)")
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
            fis.copyTo(gzos)
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
            gzis.copyTo(fos)
        }
    }

    println("$inputFile -> $outputFile (${fileSize(inputFile)} -> ${fileSize(outputFile)} bytes)")
}

private fun InputStream.copyTo(out: OutputStream) {
    val buf = ByteArray(BUFFER_SIZE)
    while (true) {
        val n = read(buf, 0, buf.size)
        if (n == -1) break
        out.write(buf, 0, n)
    }
}

private fun InputStream.drain() {
    val buf = ByteArray(BUFFER_SIZE)
    while (read(buf, 0, buf.size) != -1) {}
}
