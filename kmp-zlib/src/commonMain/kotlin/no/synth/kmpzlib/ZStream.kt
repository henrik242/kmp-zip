package no.synth.kmpzlib

// Ported from pako (src/zlib/zstream.mjs, src/zlib/constants.mjs), itself a port of zlib.

internal const val Z_NO_FLUSH = 0
internal const val Z_FINISH = 4

internal const val Z_OK = 0
internal const val Z_STREAM_END = 1
internal const val Z_NEED_DICT = 2
internal const val Z_STREAM_ERROR = -2
internal const val Z_DATA_ERROR = -3
internal const val Z_BUF_ERROR = -5

internal val EMPTY = ByteArray(0)

internal class ZStream {
    var input: ByteArray = EMPTY
    var nextIn = 0
    var availIn = 0
    var totalIn = 0L
    var output: ByteArray = EMPTY
    var nextOut = 0
    var availOut = 0
    var msg = ""
    var adler = 0
}

internal fun errorMessage(code: Int): String = when (code) {
    Z_NEED_DICT -> "need dictionary"
    Z_STREAM_END -> "stream end"
    Z_STREAM_ERROR -> "stream error"
    Z_DATA_ERROR -> "data error"
    Z_BUF_ERROR -> "buffer error"
    else -> ""
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff
