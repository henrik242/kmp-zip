package no.synth.kmpzlib

import kotlin.random.Random

/** Compresses [data], feeding at most [inChunk] bytes and offering [outChunk] bytes of room per call. */
fun compress(
    data: ByteArray,
    level: Int = Deflater.DEFAULT_COMPRESSION,
    format: ZlibFormat = ZlibFormat.ZLIB,
    inChunk: Int = 65536,
    outChunk: Int = 65536,
): ByteArray {
    val d = Deflater(level, format)
    val out = Sink()
    val buf = ByteArray(outChunk)
    var pos = 0
    while (pos < data.size) {
        val n = minOf(inChunk, data.size - pos)
        var off = pos
        var left = n
        do {
            val r = d.deflate(data, off, left, buf, 0, buf.size)
            off += r.bytesConsumed
            left -= r.bytesConsumed
            out.write(buf, r.bytesProduced)
        } while (left > 0 || r.bytesProduced == buf.size)
        pos += n
    }
    while (true) {
        val r = d.deflate(EMPTY_INPUT, 0, 0, buf, 0, buf.size, finish = true)
        out.write(buf, r.bytesProduced)
        if (r.streamEnd) break
    }
    return out.toByteArray()
}

/** Decompresses one stream; returns the output and how many input bytes it consumed. */
fun decompress(
    data: ByteArray,
    format: ZlibFormat = ZlibFormat.ZLIB,
    inChunk: Int = 65536,
    outChunk: Int = 65536,
): Pair<ByteArray, Int> {
    val inf = Inflater(format)
    val out = Sink()
    val buf = ByteArray(outChunk)
    var pos = 0
    while (!inf.isFinished) {
        val n = minOf(inChunk, data.size - pos)
        val r = inf.inflate(data, pos, n, buf, 0, buf.size)
        pos += r.bytesConsumed
        out.write(buf, r.bytesProduced)
        if (r.bytesConsumed == 0 && r.bytesProduced == 0 && !r.streamEnd) {
            error("no progress at $pos of ${data.size}")
        }
    }
    return out.toByteArray() to pos
}

val EMPTY_INPUT = ByteArray(0)

class Sink {
    private var buf = ByteArray(1024)
    private var size = 0
    fun write(b: ByteArray, len: Int) {
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        b.copyInto(buf, size, 0, len)
        size += len
    }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Inputs that exercise stored, fixed and dynamic blocks, long matches and window wrap. */
fun samples(): List<Pair<String, ByteArray>> = TestSamples().all

class TestSamples {
    private val rnd = Random(42)
    private val textData = buildString {
        val words = listOf("lorem", "ipsum", "dolor", "sit", "amet", "zip", "kotlin", "deflate", "\n")
        while (length < 200_000) append(words[rnd.nextInt(words.size)]).append(' ')
    }.encodeToByteArray()
    private val mixedData = ByteArray(300_000).also { a ->
        var i = 0
        while (i < a.size) {
            val run = rnd.nextInt(1, 600)
            if (rnd.nextBoolean()) {
                val b = rnd.nextInt(256).toByte()
                for (j in i until minOf(a.size, i + run)) a[j] = b
            } else {
                for (j in i until minOf(a.size, i + run)) a[j] = rnd.nextInt(256).toByte()
            }
            i += run
        }
    }
    val empty = "empty" to ByteArray(0)
    val oneByte = "one byte" to byteArrayOf(7)
    val short = "short" to "hello hello hello".encodeToByteArray()
    val zeros = "zeros" to ByteArray(150_000)
    val random = "random" to rnd.nextBytes(100_000)
    val text = "text" to textData
    val mixed = "mixed" to mixedData

    val all = listOf(empty, oneByte, short, zeros, random, text, mixed)
}
