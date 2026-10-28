package no.synth.kmpzip.zip

import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `read(b, off, 0)` reads nothing and returns 0, per the InputStream contract and every
 * other stream in this module. It must not reach the inflater, which would see no output
 * space, make no progress, and fail.
 */
class ZipInputStreamZeroLengthReadTest {
    @Test
    fun zeroLengthReadReturnsZeroOnDeflatedEntry() {
        ZipInputStream(TestData.binaryZip).use { zis ->
            zis.nextEntry
            val buf = ByteArray(16)
            assertEquals(0, zis.read(buf, 0, 0), "read(b, off, 0) must return 0")
            // and the entry must still be fully readable afterwards
            assertEquals(true, zis.readBytes().isNotEmpty(), "entry still readable")
        }
    }

    @Test
    fun zeroLengthReadReturnsZeroAtEndOfEntry() {
        ZipInputStream(TestData.binaryZip).use { zis ->
            zis.nextEntry
            zis.readBytes()
            assertEquals(0, zis.read(ByteArray(16), 0, 0), "read(b, off, 0) must return 0 at EOF")
        }
    }

    @Test
    fun outOfRangeReadRejected() {
        ZipInputStream(TestData.binaryZip).use { zis ->
            zis.nextEntry
            assertFailsWith<IndexOutOfBoundsException> { zis.read(ByteArray(1), 0, 8192) }
        }
    }
}
