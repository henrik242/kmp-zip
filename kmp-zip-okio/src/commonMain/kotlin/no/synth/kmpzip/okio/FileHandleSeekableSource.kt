package no.synth.kmpzip.okio

import no.synth.kmpzip.io.SeekableSource
import okio.FileHandle

/**
 * Adapts an okio [FileHandle] to a [SeekableSource], so [no.synth.kmpzip.zip.ZipFile]
 * can read entries straight off disk (or any okio filesystem) without loading the
 * whole archive into memory.
 *
 * Obtain a handle with `FileSystem.openReadOnly(path)`. okio's positional reads leave
 * the handle's position untouched and are safe to call concurrently. Closing this
 * source closes the underlying handle.
 */
class FileHandleSeekableSource(private val handle: FileHandle) : SeekableSource {
    override val size: Long get() = handle.size()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return handle.read(position, into, offset, length)
    }

    override fun close() {
        handle.close()
    }
}

fun FileHandle.asSeekableSource(): SeekableSource = FileHandleSeekableSource(this)
