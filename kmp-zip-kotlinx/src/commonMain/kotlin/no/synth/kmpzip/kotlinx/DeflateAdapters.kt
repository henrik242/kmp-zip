package no.synth.kmpzip.kotlinx

import kotlinx.io.Sink
import kotlinx.io.Source
import no.synth.kmpzip.deflate.DeflateFormat
import no.synth.kmpzip.deflate.DeflateOutputStream
import no.synth.kmpzip.deflate.InflateInputStream

fun InflateInputStream(source: Source, format: DeflateFormat = DeflateFormat.ZLIB): InflateInputStream {
    return InflateInputStream(SourceInputStream(source), format)
}

fun DeflateOutputStream(sink: Sink, format: DeflateFormat = DeflateFormat.ZLIB, level: Int = -1): DeflateOutputStream {
    return DeflateOutputStream(SinkOutputStream(sink), format, level)
}
