package no.synth.kmpzip.okio

import okio.BufferedSink
import okio.BufferedSource
import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.gzip.GzipOutputStream

fun GzipInputStream(source: BufferedSource): GzipInputStream {
    return GzipInputStream(SourceInputStream(source))
}

fun GzipOutputStream(sink: BufferedSink): GzipOutputStream {
    return GzipOutputStream(SinkOutputStream(sink))
}
