package no.synth.kmpzip.okio

import okio.BufferedSink
import okio.BufferedSource
import okio.FileHandle
import no.synth.kmpzip.zip.ZipFile
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream

fun ZipInputStream(source: BufferedSource): ZipInputStream {
    return ZipInputStream(SourceInputStream(source))
}

fun ZipOutputStream(sink: BufferedSink): ZipOutputStream {
    return ZipOutputStream(SinkOutputStream(sink))
}

/**
 * Random-access [ZipFile] over an okio [FileHandle] — seeks to each entry instead of
 * streaming the whole archive. Open the handle with `FileSystem.openReadOnly(path)`;
 * closing the returned [ZipFile] closes the handle.
 */
fun ZipFile(handle: FileHandle, password: ByteArray? = null): ZipFile =
    ZipFile(handle.asSeekableSource(), password)

/** [ZipFile] over an okio [FileHandle] with a string password. */
fun ZipFile(handle: FileHandle, password: String): ZipFile =
    ZipFile(handle.asSeekableSource(), password.encodeToByteArray())
