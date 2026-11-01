package no.synth.kmpzlib

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.TimeSource

/**
 * Throughput benchmark, skipped unless Gradle runs with `-Pkmpzlib.bench`, e.g.
 * `./gradlew :kmp-zlib:jvmTest -Pkmpzlib.bench` (or jsNodeTest, wasmJsNodeTest, linuxX64Test).
 */
class ZlibBenchmark {
    @Test
    fun benchmark() {
        val corpora = benchCorpora()
        val buf = ByteArray(CHUNK)
        // Warm up every code path before timing anything.
        for ((_, data) in corpora) for (level in listOf(1, 6, 9)) {
            decompress(compress(data, level, ZlibFormat.ZLIB, CHUNK, CHUNK), ZlibFormat.ZLIB, CHUNK, CHUNK)
        }
        println("kmp-zlib benchmark, MB/s of uncompressed data (best of $ROUNDS rounds)")
        for ((name, data) in corpora) {
            for (level in listOf(1, 6, 9)) {
                val packed = compress(data, level, ZlibFormat.ZLIB, inChunk = CHUNK, outChunk = CHUNK)
                val (unpacked, _) = decompress(packed, ZlibFormat.ZLIB, CHUNK, CHUNK)
                assertContentEquals(data, unpacked)
                val ratio = packed.size * 100.0 / data.size
                val d = measure(data.size) { deflateAll(data, level, buf) }
                val i = measure(data.size) { inflateAll(packed, buf) }
                println("${name.padEnd(8)} level $level  ratio ${fmt(ratio)}%  deflate ${fmt(d)}  inflate ${fmt(i)}")
            }
        }
        val all = corpora.last().second
        println("crc32    ${fmt(measure(all.size) { crc32(0, all, all.size, 0) })}")
        println("adler32  ${fmt(measure(all.size) { adler32(1, all, all.size, 0) })}")
    }

    /** Compresses in [CHUNK] slices into [buf], discarding the output; returns its size. */
    private fun deflateAll(data: ByteArray, level: Int, buf: ByteArray): Int {
        val d = Deflater(level, ZlibFormat.ZLIB)
        var pos = 0
        var total = 0
        while (pos < data.size) {
            val r = d.deflate(data, pos, minOf(CHUNK, data.size - pos), buf, 0, buf.size)
            pos += r.bytesConsumed
            total += r.bytesProduced
        }
        while (true) {
            val r = d.deflate(data, 0, 0, buf, 0, buf.size, finish = true)
            total += r.bytesProduced
            if (r.streamEnd) return total
        }
    }

    /** Decompresses in [CHUNK] slices into [buf], discarding the output; returns its size. */
    private fun inflateAll(packed: ByteArray, buf: ByteArray): Int {
        val inf = Inflater(ZlibFormat.ZLIB)
        var pos = 0
        var total = 0
        while (!inf.isFinished) {
            val r = inf.inflate(packed, pos, minOf(CHUNK, packed.size - pos), buf, 0, buf.size)
            pos += r.bytesConsumed
            total += r.bytesProduced
        }
        return total
    }

    private inline fun measure(bytes: Int, block: () -> Any): Double {
        var sink = 0
        repeat(WARMUP) { sink += block().hashCode() }
        var best = Double.MAX_VALUE
        repeat(ROUNDS) {
            val mark = TimeSource.Monotonic.markNow()
            sink += block().hashCode()
            val s = mark.elapsedNow().inWholeNanoseconds / 1e9
            if (s < best) best = s
        }
        if (sink == 42) println()
        return bytes / best / 1e6
    }

    private fun fmt(v: Double): String {
        val r = kotlin.math.round(v * 10).toLong()
        return "${r / 10}.${r % 10}".padStart(6)
    }

    private companion object {
        const val WARMUP = 2
        const val ROUNDS = 10
        const val CHUNK = 65536
        const val SIZE = 4 shl 20
    }

    private fun benchCorpora(): List<Pair<String, ByteArray>> {
        val rnd = Random(2024)
        val words = ("the of and to in is that it was for on are as with his they at be this from have or " +
            "by one had not but what all were when we there can an your which their said if do will each " +
            "about how up out them then she many some so these would other into has more her two like him " +
            "see time could no make than first been its who now people my made over did down only way find " +
            "use may water long little very after words called just where most know").split(' ')
        val text = buildString {
            while (length < SIZE) {
                val n = rnd.nextInt(5, 18)
                append(words[rnd.nextInt(words.size)])
                repeat(n - 1) { append(' ').append(words[rnd.nextInt(words.size)]) }
                append(if (rnd.nextInt(6) == 0) ".\n" else ". ")
            }
        }.encodeToByteArray().copyOf(SIZE)

        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"bench\">\n<trk><trkseg>\n")
            var lat = 59.9139
            var lon = 10.7522
            var ele = 12.0
            var t = 0
            while (length < SIZE) {
                lat += (rnd.nextDouble() - 0.5) * 0.0002
                lon += (rnd.nextDouble() - 0.5) * 0.0002
                ele += (rnd.nextDouble() - 0.5) * 0.8
                t += rnd.nextInt(1, 4)
                val hh = (t / 3600) % 24
                val mm = (t / 60) % 60
                val ss = t % 60
                append("  <trkpt lat=\"").append(lat.toString().take(10)).append("\" lon=\"")
                    .append(lon.toString().take(10)).append("\"><ele>").append(ele.toString().take(6))
                    .append("</ele><time>2026-06-01T")
                    .append(hh.toString().padStart(2, '0')).append(':')
                    .append(mm.toString().padStart(2, '0')).append(':')
                    .append(ss.toString().padStart(2, '0')).append("Z</time></trkpt>\n")
            }
        }.encodeToByteArray().copyOf(SIZE)

        val pattern = "abcabcabd0123456789".encodeToByteArray()
        val repetitive = ByteArray(SIZE) { pattern[it % pattern.size] }
        val random = rnd.nextBytes(SIZE)
        return listOf("text" to text, "gpx" to gpx, "repeat" to repetitive, "random" to random)
    }
}
