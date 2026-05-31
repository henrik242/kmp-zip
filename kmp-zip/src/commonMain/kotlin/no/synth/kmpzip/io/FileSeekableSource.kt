package no.synth.kmpzip.io

/**
 * Opens a [SeekableSource] over the file at [path], reading lazily by position
 * instead of loading the whole file into memory.
 *
 * This is the memory-cheap way to drive [no.synth.kmpzip.zip.ZipFile] over a large
 * archive: only the central directory and the entries you actually read are touched.
 *
 * **Availability.** JVM (and Android), Apple, Linux, and Windows native targets, and
 * wasmJs running under **Node.js**. There is no synchronous random file access in a
 * browser, so calling this in a browser wasmJs context fails — use
 * [ByteArraySeekableSource] there.
 *
 * **Threading.** The JVM implementation (backed by a positional `FileChannel`) is
 * safe to read from concurrently. The native and Node implementations seek-then-read
 * a single shared handle: interleaving several entry streams from one thread is fine,
 * but for concurrent reads across threads, open one source per thread (or use
 * [ByteArraySeekableSource]).
 *
 * The returned source holds an open file handle until [SeekableSource.close] — closing
 * the owning [no.synth.kmpzip.zip.ZipFile] closes it for you.
 *
 * @throws Exception if the file cannot be opened.
 */
expect fun fileSeekableSource(path: String): SeekableSource
