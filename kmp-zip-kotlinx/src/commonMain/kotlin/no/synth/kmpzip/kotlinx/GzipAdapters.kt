package no.synth.kmpzip.kotlinx

import kotlinx.io.Sink
import kotlinx.io.Source
import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.gzip.GzipOutputStream

fun GzipInputStream(source: Source): GzipInputStream {
    return GzipInputStream(SourceInputStream(source))
}

fun GzipOutputStream(sink: Sink): GzipOutputStream {
    return GzipOutputStream(SinkOutputStream(sink))
}
