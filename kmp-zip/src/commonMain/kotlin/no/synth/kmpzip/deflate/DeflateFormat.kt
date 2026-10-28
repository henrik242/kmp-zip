package no.synth.kmpzip.deflate

/** How DEFLATE data is framed. */
enum class DeflateFormat {
    /** Bare DEFLATE data (RFC 1951) with no header or checksum, as stored in ZIP entries. */
    RAW,

    /** DEFLATE data in a zlib wrapper (RFC 1950): a 2-byte header and an Adler-32 trailer. */
    ZLIB,
}
