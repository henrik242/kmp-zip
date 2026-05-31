package no.synth.kmpzip.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

actual fun fileSeekableSource(path: String): SeekableSource = JvmFileSeekableSource(path)

private class JvmFileSeekableSource(path: String) : SeekableSource {
    // Positional reads (read(buffer, position)) leave the channel position untouched
    // and are safe to call concurrently.
    private val channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ)

    override val size: Long get() = channel.size()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return channel.read(ByteBuffer.wrap(into, offset, length), position)
    }

    override fun close() {
        channel.close()
    }
}
