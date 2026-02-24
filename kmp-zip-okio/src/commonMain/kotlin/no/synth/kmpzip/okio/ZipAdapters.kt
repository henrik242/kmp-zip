package no.synth.kmpzip.okio

import okio.BufferedSink
import okio.BufferedSource
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipOutputStream

fun ZipInputStream(source: BufferedSource): ZipInputStream {
    return ZipInputStream(SourceInputStream(source))
}

fun ZipOutputStream(sink: BufferedSink): ZipOutputStream {
    return ZipOutputStream(SinkOutputStream(sink))
}
