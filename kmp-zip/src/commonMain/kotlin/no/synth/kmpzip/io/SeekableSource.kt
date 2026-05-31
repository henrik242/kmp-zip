package no.synth.kmpzip.io

/**
 * A random-access, read-only byte source.
 *
 * Reads are **positional**: each [read] specifies its own absolute [position], so
 * there is no cursor to track between calls. That maps cleanly onto `pread`,
 * `FileChannel.read(buf, position)`, and `fs.readSync(fd, …, position)`, and lets
 * [no.synth.kmpzip.zip.ZipFile] extract several entries from one source by seeking to
 * each in turn.
 *
 * Whether concurrent calls from multiple threads are safe is left to the
 * implementation: [ByteArraySeekableSource] is (it only reads immutable bytes), as is
 * the JVM file source; the native/Node file sources are not — see [fileSeekableSource].
 *
 * This is the abstraction [no.synth.kmpzip.zip.ZipFile] reads through.
 * [ByteArraySeekableSource] works on every target including wasmJs in the browser;
 * [fileSeekableSource] avoids materializing the whole archive in memory where
 * synchronous random file access exists.
 */
interface SeekableSource : Closeable {
    /** Total number of bytes in the source. */
    val size: Long

    /**
     * Reads up to [length] bytes starting at absolute [position] into [into] at
     * [offset].
     *
     * @return the number of bytes read (which may be fewer than [length]), or `-1`
     *   at end of input ([position] at or beyond [size]). A return of `0` is only
     *   possible when [length] is `0` — implementations must not return `0` for a
     *   non-empty request, since callers treat that as a hard failure.
     */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}
