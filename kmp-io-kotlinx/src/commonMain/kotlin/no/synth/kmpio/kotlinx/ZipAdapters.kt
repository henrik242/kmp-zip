package no.synth.kmpio.kotlinx

import kotlinx.io.Sink
import kotlinx.io.Source
import no.synth.kmpio.zip.ZipInputStream
import no.synth.kmpio.zip.ZipOutputStream

fun ZipInputStream(source: Source): ZipInputStream {
    return ZipInputStream(SourceInputStream(source))
}

fun ZipOutputStream(sink: Sink): ZipOutputStream {
    return ZipOutputStream(SinkOutputStream(sink))
}
