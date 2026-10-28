package no.synth.kmpzip.zip

import no.synth.kmpzip.io.SeekableSource
import no.synth.kmpzip.io.SeekableSourceInputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Archives past the 4 GiB limits, held in memory by storing runs of zero bytes as
 * holes. Only the JVM's platform CRC and zlib are fast enough to push 4 GiB through
 * in a unit test.
 */
class Zip64LargeJvmTest {

    private class Segment(val start: Long, val length: Long, val data: ByteArray?)

    /** Records written bytes, keeping all-zero writes as holes instead of data. */
    private class SparseArchive : OutputStream(), SeekableSource {
        private val segments = ArrayList<Segment>()
        override var size: Long = 0
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            val zero = (off until off + len).all { b[it].toInt() == 0 }
            val last = segments.lastOrNull()
            if (zero && last != null && last.data == null) {
                segments[segments.size - 1] = Segment(last.start, last.length + len, null)
            } else {
                segments.add(Segment(size, len.toLong(), if (zero) null else b.copyOfRange(off, off + len)))
            }
            size += len
        }

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            if (length == 0) return 0
            var lo = 0
            var hi = segments.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (segments[mid].start <= position) lo = mid else hi = mid - 1
            }
            val seg = segments[lo]
            val inSeg = position - seg.start
            val n = minOf(length.toLong(), seg.length - inSeg).toInt()
            val data = seg.data
            if (data == null) into.fill(0, offset, offset + n) else data.copyInto(into, offset, inSeg.toInt(), inSeg.toInt() + n)
            return n
        }

        fun inputStream() = SeekableSourceInputStream(this, 0)

        override fun close() {}
    }

    private val chunk = ByteArray(1 shl 20)
    private val bigSize = (4L shl 30) + 1234567 // past 4 GiB, not a multiple of the chunk

    private fun ZipOutputStream.writeZeros(total: Long) {
        var left = total
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            write(chunk, 0, n)
            left -= n
        }
    }

    private fun zerosCrc(total: Long): Long {
        val crc = java.util.zip.CRC32()
        var left = total
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            crc.update(chunk, 0, n)
            left -= n
        }
        return crc.value
    }

    /** Reads [input] to the end, failing on any non-zero byte; returns the count. */
    private fun drainZeros(input: java.io.InputStream): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) return total
            for (i in 0 until n) if (buf[i].toInt() != 0) error("non-zero byte at ${total + i}")
            total += n
        }
    }

    @Test
    fun deflatedEntryOfUnknownSizePast4GiB() {
        val archive = SparseArchive()
        ZipOutputStream(archive).use { zos ->
            zos.setLevel(1)
            zos.putNextEntry(ZipEntry("big.bin"))
            zos.writeZeros(bigSize)
            zos.putNextEntry(ZipEntry("after.txt"))
            zos.write("after".encodeToByteArray())
        }

        ZipFile(archive).use { zip ->
            assertEquals(bigSize, assertNotNull(zip.getEntry("big.bin")).size)
            assertEquals("after", zip.getInputStream("after.txt").use { it.readBytes().decodeToString() })
        }

        // The local header has no ZIP64 field, so the 8-byte descriptor is found from
        // the inflater's byte counts.
        ZipInputStream(archive.inputStream()).use { zis ->
            assertEquals("big.bin", assertNotNull(zis.nextEntry).name)
            assertEquals(bigSize, drainZeros(zis))
            assertEquals("after.txt", assertNotNull(zis.nextEntry).name)
            assertEquals("after", zis.readBytes().decodeToString())
            assertNull(zis.nextEntry)
        }

        java.util.zip.ZipInputStream(archive.inputStream()).use { zis ->
            assertEquals("big.bin", assertNotNull(zis.nextEntry).name)
            assertEquals(bigSize, drainZeros(zis))
            assertEquals("after.txt", assertNotNull(zis.nextEntry).name)
            assertEquals("after", zis.readBytes().decodeToString())
        }

        // Small enough on disk once compressed, so java.util.zip.ZipFile can check the
        // central directory.
        val file = File.createTempFile("kmpzip-zip64", ".zip")
        try {
            file.outputStream().use { out -> archive.inputStream().copyTo(out) }
            java.util.zip.ZipFile(file).use { zf ->
                assertEquals(bigSize, zf.getEntry("big.bin").size)
                assertEquals("after", zf.getInputStream(zf.getEntry("after.txt")).readBytes().decodeToString())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun entryOffsetsPast4GiB() {
        val archive = SparseArchive()
        ZipOutputStream(archive).use { zos ->
            val big = ZipEntry("big.bin")
            big.method = ZipConstants.STORED
            big.size = bigSize
            big.crc = zerosCrc(bigSize)
            zos.putNextEntry(big)
            zos.writeZeros(bigSize)
            zos.putNextEntry(ZipEntry("after.txt"))
            zos.write("after".encodeToByteArray())
        }

        // The central directory starts past 4 GiB, so the ZIP64 end records are written.
        val tail = archive.inputStream().apply { skip(archive.size - 42) }.readBytes()
        assertEquals(ZipConstants.ZIP64_END_OF_CENTRAL_DIR_LOCATOR_SIGNATURE.toLong(), readLeUInt(tail, 0))
        assertEquals(ZipConstants.ZIP64_MAGIC, readLeUInt(tail, 20 + 16))

        ZipFile(archive).use { zip ->
            val big = assertNotNull(zip.getEntry("big.bin"))
            assertEquals(bigSize, big.size)
            assertEquals(bigSize, big.compressedSize)
            assertEquals("after", zip.getInputStream("after.txt").use { it.readBytes().decodeToString() })
        }

        ZipInputStream(archive.inputStream()).use { zis ->
            assertEquals(bigSize, assertNotNull(zis.nextEntry).size)
            // Skipped without reading: the size has to come from the local ZIP64 field.
            assertEquals("after.txt", assertNotNull(zis.nextEntry).name)
            assertEquals("after", zis.readBytes().decodeToString())
            assertNull(zis.nextEntry)
        }

        java.util.zip.ZipInputStream(archive.inputStream()).use { zis ->
            assertEquals(bigSize, assertNotNull(zis.nextEntry).size)
            assertEquals(bigSize, drainZeros(zis))
            assertEquals("after.txt", assertNotNull(zis.nextEntry).name)
            assertEquals("after", zis.readBytes().decodeToString())
        }
    }
}
