package no.synth.kmpzip.gzip

/** Sniff, not validation: true if [data] starts with the GZIP magic `0x1f 0x8b`. */
fun isGzip(data: ByteArray): Boolean =
    data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
