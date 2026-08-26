package no.synth.kmpzip.zip

import no.synth.kmpzip.internal.Uint8Array
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// Conformance suite for the one marshalling expect and both its actuals. js
// aliases a typed array over the ByteArray's buffer, wasmJs copies via a
// Latin-1 String; the two have to agree byte for byte, and neither is covered
// directly by the stream tests.
class PakoMarshalTest {

    @Test
    fun roundTripsEveryByteValue() {
        val src = ByteArray(256) { it.toByte() }
        val out = ByteArray(256)
        copyUint8ToByteArray(byteArrayToUint8Array(src, 0, 256), 0, out, 0, 256)
        assertContentEquals(src, out)
    }

    @Test
    fun marshalsFromNonZeroSourceOffset() {
        val src = ByteArray(300) { (it * 7).toByte() }
        val arr = byteArrayToUint8Array(src, 7, 13)
        assertEquals(13, arr.length)
        val out = ByteArray(13)
        copyUint8ToByteArray(arr, 0, out, 0, 13)
        assertContentEquals(src.copyOfRange(7, 20), out)
    }

    @Test
    fun copiesToNonZeroDestinationOffsetWithoutTouchingNeighbours() {
        val src = ByteArray(10) { (it + 1).toByte() }
        val out = ByteArray(20) { 0x5A }
        copyUint8ToByteArray(byteArrayToUint8Array(src, 0, 10), 2, out, 5, 6)
        assertContentEquals(src.copyOfRange(2, 8), out.copyOfRange(5, 11))
        for (i in 0 until 5) assertEquals(0x5A, out[i], "clobbered out[$i]")
        for (i in 11 until 20) assertEquals(0x5A, out[i], "clobbered out[$i]")
    }

    @Test
    fun zeroLengthMarshalIsANoOp() {
        val src = ByteArray(8) { 1 }
        assertEquals(0, byteArrayToUint8Array(src, 3, 0).length)
        val out = ByteArray(4) { 9 }
        copyUint8ToByteArray(byteArrayToUint8Array(src, 0, 8), 0, out, 0, 0)
        assertContentEquals(ByteArray(4) { 9 }, out)
    }

    @Test
    fun roundTripsAcrossLatin1ChunkBoundary() {
        // wasmJs marshals in 16384-char slices; straddle two boundaries with the
        // 0x80..0x9F range that windows-1252 would remap.
        val size = 16384 * 2 + 1
        val src = ByteArray(size) { (0x80 + (it % 32)).toByte() }
        val out = ByteArray(size)
        copyUint8ToByteArray(byteArrayToUint8Array(src, 0, size), 0, out, 0, size)
        assertContentEquals(src, out)
    }

    @Test
    fun deflaterDoesNotReadInputAfterDeflateReturns() {
        // Canary for the js aliasing shortcut: the pushed Uint8Array is a view
        // over the caller's ByteArray, which is only sound because pako copies
        // input into its window during push() and never reads strm.input
        // afterwards. Scribbling the buffer between calls fails here if that
        // ever stops holding.
        val original = ByteArray(128 * 1024) { (it * 31 % 251).toByte() }
        val deflater = PlatformDeflater()
        deflater.init(level = 6, nowrap = true, gzip = false)
        val compressed = ByteArrayCollector()
        val chunk = ByteArray(8192)
        val out = ByteArray(4096)
        var read = 0
        while (read < original.size) {
            val n = minOf(chunk.size, original.size - read)
            original.copyInto(chunk, 0, read, read + n)
            var consumed = 0
            while (consumed < n) {
                val r = deflater.deflate(chunk, consumed, n - consumed, out, 0, out.size, finish = false)
                consumed += r.bytesConsumed
                compressed.add(out, r.bytesProduced)
                chunk.fill(0xEE.toByte())
            }
            read += n
        }
        while (!deflater.isFinished) {
            val r = deflater.deflate(chunk, 0, 0, out, 0, out.size, finish = true)
            compressed.add(out, r.bytesProduced)
        }
        deflater.end()

        val inflater = PlatformInflater()
        inflater.init(nowrap = true, gzip = false)
        val restored = ByteArrayCollector()
        val packed = compressed.toByteArray()
        var pos = 0
        while (true) {
            val r = inflater.inflate(packed, pos, packed.size - pos, out, 0, out.size)
            pos += r.bytesConsumed
            restored.add(out, r.bytesProduced)
            if (r.streamEnd) break
            if (r.bytesConsumed == 0 && r.bytesProduced == 0) break
        }
        inflater.end()
        assertContentEquals(original, restored.toByteArray())
    }
}

private class ByteArrayCollector {
    private val bytes = mutableListOf<Byte>()
    fun add(buf: ByteArray, len: Int) {
        for (i in 0 until len) bytes.add(buf[i])
    }
    fun toByteArray() = bytes.toByteArray()
}
