package no.synth.kmpzip.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@OptIn(ExperimentalForeignApi::class)
actual fun fileSeekableSource(path: String): SeekableSource = NativeFileSeekableSource(path)

// fseek/fread share one handle, so this is single-thread-only — see fileSeekableSource.
// `.convert()` papers over the platform offset-type difference (64-bit off_t on POSIX,
// 32-bit long on Windows). Consequence: on Windows both ftell (size detection) and
// fseek top out at 2 GB; past that they fail and we throw rather than read garbage.
// Non-ZIP64 archives are < 4 GB and the realistic large-archive targets (JVM/Apple)
// use 64-bit offsets, so the Windows ceiling is acceptable.
@OptIn(ExperimentalForeignApi::class)
private class NativeFileSeekableSource(path: String) : SeekableSource {
    private val file = fopen(path, "rb") ?: throw Exception("Cannot open file: $path")
    private var closed = false

    override val size: Long = run {
        if (fseek(file, 0.convert(), SEEK_END) != 0) throw Exception("Cannot seek to end of file: $path")
        val end = ftell(file).convert<Long>()
        // Negative means ftell failed (e.g. a > 2 GB file on 32-bit Windows offsets) —
        // fail loudly instead of letting a bogus size silently truncate every read.
        if (end < 0) throw Exception("Cannot determine size of file (> 2 GB on this platform?): $path")
        if (fseek(file, 0.convert(), SEEK_SET) != 0) throw Exception("Cannot rewind file: $path")
        end
    }

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IllegalStateException("Source closed")
        if (length == 0) return 0
        if (position >= size) return -1
        if (fseek(file, position.convert(), SEEK_SET) != 0) {
            throw Exception("Seek to position $position failed (file size $size)")
        }
        val toRead = minOf(length.toLong(), size - position).toInt()
        return into.usePinned { pinned ->
            val n = fread(pinned.addressOf(offset), 1.convert(), toRead.convert(), file).convert<Int>()
            when {
                n > 0 -> n
                // position < size was checked above, so a 0 here is a read error, not EOF —
                // distinguish so we never return 0 for a non-empty request (contract).
                feof(file) != 0 -> -1
                else -> throw Exception("Read at position $position failed")
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            fclose(file)
        }
    }
}
