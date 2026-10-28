package no.synth.kmpzip.okio

import okio.BufferedSink
import okio.BufferedSource
import no.synth.kmpzip.deflate.DeflateFormat
import no.synth.kmpzip.deflate.DeflateOutputStream
import no.synth.kmpzip.deflate.InflateInputStream

fun InflateInputStream(source: BufferedSource, format: DeflateFormat = DeflateFormat.ZLIB): InflateInputStream {
    return InflateInputStream(SourceInputStream(source), format)
}

fun DeflateOutputStream(sink: BufferedSink, format: DeflateFormat = DeflateFormat.ZLIB, level: Int = -1): DeflateOutputStream {
    return DeflateOutputStream(SinkOutputStream(sink), format, level)
}
