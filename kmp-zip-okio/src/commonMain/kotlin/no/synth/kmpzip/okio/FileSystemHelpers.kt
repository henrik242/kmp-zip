package no.synth.kmpzip.okio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import no.synth.kmpzip.crypto.AesStrength
import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.zip.ZipConstants
import no.synth.kmpzip.zip.ZipEncryption
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream
import no.synth.kmpzip.zip.safeEntrySegments
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.coroutines.CoroutineContext

private const val BUFFER_SIZE = 8192

// Recursion depth cap for zipTo's directory walk. A symlink loop in a source
// directory would otherwise recurse indefinitely; 1024 levels is far past any
// realistic legitimate tree.
private const val MAX_DEPTH = 1024

/**
 * Compresses [sources] (files and/or directories, walked recursively) into a ZIP
 * at [target]. Overwrites [target] if it exists; creates parent directories.
 *
 * Runs the I/O on [dispatcher] — defaults to `Dispatchers.IO` on JVM and
 * `Dispatchers.Default` elsewhere (native and wasmJs). Cooperatively cancellable
 * at entry boundaries and within `copyStream`. On wasmJs the runtime is
 * single-threaded, so a `withTimeout` set on an in-flight call cannot fire until
 * the body next suspends — pre-cancel the scope if you need to abort up front.
 *
 * Browser callers: okio does not provide a `FileSystem.SYSTEM` for browser
 * contexts; pass an in-memory `FakeFileSystem` (or similar) as the receiver.
 *
 * Symlinks are followed and Unix mode bits are not preserved; this matches the
 * behavior of the kmpzip CLI. A symlink loop is detected via a depth cap and
 * surfaces as `IllegalArgumentException`.
 *
 * @throws IllegalArgumentException for an invalid [method] / [level], for
 *  encryption parameters supplied without a [password], or for a directory
 *  walk deeper than 1024 levels (likely a symlink loop).
 */
suspend fun FileSystem.zipTo(
    target: Path,
    sources: List<Path>,
    password: String? = null,
    encryption: ZipEncryption = ZipEncryption.AES,
    aesStrength: AesStrength = AesStrength.AES_256,
    method: Int = ZipConstants.DEFLATED,
    level: Int? = null,
    dispatcher: CoroutineContext = defaultZipDispatcher,
) {
    requireEncryptionConsistency(password, encryption, aesStrength)
    withContext(dispatcher) {
        target.parent?.let { createDirectories(it) }
        // Wrap the buffered sink in SinkOutputStream and adopt it via zos.use {}
        // immediately, so any throw from setMethod/setLevel still cleans up the
        // underlying file resource.
        val out = SinkOutputStream(sink(target).buffer())
        ZipOutputStream(out, password?.encodeToByteArray(), encryption, aesStrength).use { zos ->
            zos.setMethod(method)
            if (level != null) zos.setLevel(level)
            for (src in sources) addRecursive(this@zipTo, zos, src, prefix = "", depth = 0)
        }
    }
}

/**
 * Extracts the ZIP at [archive] into [target]. Creates [target] if missing.
 * Rejects entries whose names would escape [target] (absolute paths, drive
 * letters, parent traversal, control chars).
 *
 * Runs the I/O on [dispatcher] — defaults to `Dispatchers.IO` on JVM and
 * `Dispatchers.Default` elsewhere (native and wasmJs). Cooperatively cancellable.
 * On wasmJs see the cancellation caveat on [zipTo].
 *
 * @throws IllegalArgumentException for unsafe entry names or password/format mismatches.
 */
suspend fun FileSystem.unzipFrom(
    archive: Path,
    target: Path,
    password: String? = null,
    dispatcher: CoroutineContext = defaultZipDispatcher,
) {
    withContext(dispatcher) {
        createDirectories(target)
        val input = SourceInputStream(source(archive).buffer())
        ZipInputStream(input, password?.encodeToByteArray()).use { zis ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zis.nextEntry ?: break
                val safe = safeEntrySegments(entry.name).fold(target) { acc, seg -> acc / seg }
                if (entry.isDirectory) {
                    createDirectories(safe)
                } else {
                    safe.parent?.let { createDirectories(it) }
                    SinkOutputStream(sink(safe).buffer()).use { fos ->
                        copyStream(zis, fos, buf)
                    }
                }
            }
        }
    }
}

private fun requireEncryptionConsistency(
    password: String?,
    encryption: ZipEncryption,
    aesStrength: AesStrength,
) {
    require(password != null || (encryption == ZipEncryption.AES && aesStrength == AesStrength.AES_256)) {
        "encryption / aesStrength were customized but no password was provided — entries would not be encrypted"
    }
}

private suspend fun addRecursive(
    fs: FileSystem,
    zos: ZipOutputStream,
    file: Path,
    prefix: String,
    depth: Int,
) {
    currentCoroutineContext().ensureActive()
    require(depth <= MAX_DEPTH) {
        "zipTo: directory depth exceeds $MAX_DEPTH levels at '$file' — likely a symlink loop"
    }
    val md = fs.metadata(file)
    val entryName = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
    if (md.isDirectory) {
        zos.putNextEntry(ZipEntry("$entryName/"))
        zos.closeEntry()
        for (child in fs.list(file).sortedBy { it.name }) {
            addRecursive(fs, zos, child, entryName, depth + 1)
        }
    } else {
        zos.putNextEntry(ZipEntry(entryName))
        SourceInputStream(fs.source(file).buffer()).use { fis ->
            copyStream(fis, zos, ByteArray(BUFFER_SIZE))
        }
        zos.closeEntry()
    }
}

private suspend fun copyStream(input: InputStream, output: OutputStream, buf: ByteArray) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buf, 0, buf.size)
        if (n == -1) break
        output.write(buf, 0, n)
    }
}
