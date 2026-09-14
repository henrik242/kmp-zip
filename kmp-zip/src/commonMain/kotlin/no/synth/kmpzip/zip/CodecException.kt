package no.synth.kmpzip.zip

/**
 * The inflate codec rejected the compressed data: zlib `Z_DATA_ERROR` on native, a pako
 * error on js/wasmJs, or `java.util.zip.DataFormatException` on the JVM. Distinct from
 * programmer errors such as an uninitialized inflater or out-of-bounds arguments, so a
 * caller can map a genuine data fault to a format exception without swallowing a bug.
 */
internal class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
