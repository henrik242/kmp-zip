package no.synth.kmpzip.cli

import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.gzip.GzipOutputStream
import no.synth.kmpzip.zip.ZipEncryption
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(1)
    }

    try {
        when (args[0]) {
            "list", "l" -> list(args.drop(1))
            "extract", "x" -> extract(args.drop(1))
            "create", "c" -> create(args.drop(1))
            "gzip", "gz" -> gzip(args.drop(1))
            "gunzip", "ungzip", "guz" -> gunzip(args.drop(1))
            "help", "-h", "--help" -> printUsage()
            else -> {
                System.err.println("Unknown command: ${args[0]}")
                printUsage()
                exitProcess(1)
            }
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

private fun printUsage() {
    val usage = """
        kmp-zip-cli — ZIP/GZIP command-line tool powered by kmp-zip

        Usage: kmp-zip-cli <command> [options] [args]

        Commands:
          list,    l   <file.zip> [-p password]           List ZIP contents
          extract, x   <file.zip> [-d dir] [-p password]  Extract ZIP contents
          create,  c   <file.zip> [-p password] [--legacy] <files..> Create ZIP from files
          gzip,    gz  <file>                              GZIP compress a file
          gunzip,  guz <file.gz>                           GZIP decompress a file
          help                                             Show this help

        Options:
          -p <password>   Password for encrypted ZIP files
          -d <dir>        Output directory for extraction (default: current dir)
          --legacy        Use legacy ZipCrypto encryption instead of AES (create only)
    """.trimIndent()
    println(usage)
}

private class CliArgs(args: List<String>) {
    var password: String? = null
    var outputDir: String? = null
    var legacy: Boolean = false
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
                else -> positional.add(args[i])
            }
            i++
        }
    }
}

// -- list --

private fun list(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmp-zip-cli list <file.zip> [-p password]" }

    val file = File(cli.positional[0])
    require(file.exists()) { "File not found: ${file.path}" }

    val fis = FileInputStream(file)
    val password = cli.password
    val zis = if (password != null) {
        ZipInputStream(fis, password.encodeToByteArray())
    } else {
        ZipInputStream(fis)
    }

    zis.use {
        println("%-8s  %-12s  %-12s  %s".format("Method", "Size", "Compressed", "Name"))
        println("-".repeat(60))

        while (true) {
            val entry = it.nextEntry ?: break
            // Drain entry data so sizes get populated from data descriptors
            val drain = ByteArray(8192)
            while (it.read(drain, 0, drain.size) != -1) { /* discard */ }

            val method = when (entry.method) {
                0 -> "STORED"
                8 -> "DEFLATED"
                else -> "m:${entry.method}"
            }
            val size = if (entry.size >= 0) entry.size.toString() else "?"
            val compressedSize = if (entry.compressedSize >= 0) entry.compressedSize.toString() else "?"
            println("%-8s  %12s  %12s  %s".format(method, size, compressedSize, entry.name))
        }
    }
}

// -- extract --

private fun extract(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmp-zip-cli extract <file.zip> [-d dir] [-p password]" }

    val file = File(cli.positional[0])
    require(file.exists()) { "File not found: ${file.path}" }

    val outputDir = File(cli.outputDir ?: ".")
    outputDir.mkdirs()

    val fis = FileInputStream(file)
    val password = cli.password
    val zis = if (password != null) {
        ZipInputStream(fis, password.encodeToByteArray())
    } else {
        ZipInputStream(fis)
    }

    zis.use {
        while (true) {
            val entry = it.nextEntry ?: break
            val target = File(outputDir, entry.name).also { f ->
                // Guard against zip slip
                require(f.canonicalPath.startsWith(outputDir.canonicalPath + File.separator) ||
                        f.canonicalPath == outputDir.canonicalPath) {
                    "Entry is outside of the target dir: ${entry.name}"
                }
            }

            if (entry.isDirectory) {
                target.mkdirs()
                println("  created: ${entry.name}")
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { fos ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = it.read(buf, 0, buf.size)
                        if (n == -1) break
                        fos.write(buf, 0, n)
                    }
                }
                println("extracted: ${entry.name} (${target.length()} bytes)")
            }
        }
    }
}

// -- create --

private fun create(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.size >= 2) {
        "Usage: kmp-zip-cli create <file.zip> [-p password] <files..>"
    }

    val zipFile = File(cli.positional[0])
    val inputFiles = cli.positional.drop(1).map { path ->
        File(path).also { f ->
            require(f.exists()) { "File not found: ${f.path}" }
        }
    }

    val fos = FileOutputStream(zipFile)
    val password = cli.password
    val encryption = if (cli.legacy) ZipEncryption.LEGACY else ZipEncryption.AES
    val zos = if (password != null) {
        ZipOutputStream(fos, password.encodeToByteArray(), encryption)
    } else {
        ZipOutputStream(fos)
    }

    zos.use {
        for (inputFile in inputFiles) {
            addToZip(it, inputFile, "")
        }
    }

    println("Created ${zipFile.path} (${zipFile.length()} bytes)")
}

private fun addToZip(zos: ZipOutputStream, file: File, prefix: String) {
    val entryName = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"

    if (file.isDirectory) {
        zos.putNextEntry(ZipEntry("$entryName/"))
        zos.closeEntry()
        println("  added: $entryName/")

        file.listFiles()?.sorted()?.forEach { child ->
            addToZip(zos, child, entryName)
        }
    } else {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            while (true) {
                val n = fis.read(buf, 0, buf.size)
                if (n == -1) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
        println("  added: $entryName (${file.length()} bytes)")
    }
}

// -- gzip --

private fun gzip(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmp-zip-cli gzip <file>" }

    val inputFile = File(cli.positional[0])
    require(inputFile.exists()) { "File not found: ${inputFile.path}" }

    val outputFile = File("${inputFile.path}.gz")

    FileInputStream(inputFile).use { fis ->
        GzipOutputStream(FileOutputStream(outputFile)).use { gzos ->
            val buf = ByteArray(8192)
            while (true) {
                val n = fis.read(buf, 0, buf.size)
                if (n == -1) break
                gzos.write(buf, 0, n)
            }
        }
    }

    println("${inputFile.path} -> ${outputFile.path} (${inputFile.length()} -> ${outputFile.length()} bytes)")
}

// -- gunzip --

private fun gunzip(args: List<String>) {
    val cli = CliArgs(args)
    require(cli.positional.isNotEmpty()) { "Usage: kmp-zip-cli gunzip <file.gz>" }

    val inputFile = File(cli.positional[0])
    require(inputFile.exists()) { "File not found: ${inputFile.path}" }

    val outputName = if (inputFile.path.endsWith(".gz")) {
        inputFile.path.removeSuffix(".gz")
    } else {
        "${inputFile.path}.out"
    }
    val outputFile = File(outputName)

    GzipInputStream(FileInputStream(inputFile)).use { gzis ->
        FileOutputStream(outputFile).use { fos ->
            val buf = ByteArray(8192)
            while (true) {
                val n = gzis.read(buf, 0, buf.size)
                if (n == -1) break
                fos.write(buf, 0, n)
            }
        }
    }

    println("${inputFile.path} -> ${outputFile.path} (${inputFile.length()} -> ${outputFile.length()} bytes)")
}
