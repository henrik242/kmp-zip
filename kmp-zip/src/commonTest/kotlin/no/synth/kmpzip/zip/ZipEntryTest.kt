package no.synth.kmpzip.zip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipEntryTest {

    // -1 is the "unset" sentinel: ZipOutputStream substitutes the default method,
    // rejects STORED entries without a size or crc, and ZipInputStream skips CRC
    // verification until a data descriptor supplies one.
    @Test
    fun unsetFieldsDefaultToTheSentinel() {
        val entry = ZipEntry("hello.txt")
        assertEquals("hello.txt", entry.name)
        assertEquals(-1L, entry.size)
        assertEquals(-1L, entry.compressedSize)
        assertEquals(-1L, entry.crc)
        assertEquals(-1, entry.method)
        assertEquals(-1L, entry.time)
        assertNull(entry.comment)
        assertNull(entry.extra)
        assertFalse(entry.isDirectory)
    }

    @Test
    fun isDirectoryFollowsTheTrailingSlash() {
        assertTrue(ZipEntry("dir/").isDirectory)
        assertFalse(ZipEntry("dir").isDirectory)
    }
}
