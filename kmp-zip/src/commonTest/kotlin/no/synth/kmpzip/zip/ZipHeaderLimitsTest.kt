package no.synth.kmpzip.zip

import no.synth.kmpzip.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipHeaderLimitsTest {

    private val tooLong = "x".repeat(0x10000)

    @Test
    fun longestNameIsWritten() {
        val name = "y".repeat(0xFFFF)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { it.putNextEntry(ZipEntry(name)) }
        assertEquals(name, ZipFile(baos.toByteArray()).use { it.entries.single().name })
    }

    @Test
    fun nameTooLongRejected() {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        assertFailsWith<IllegalArgumentException> { zos.putNextEntry(ZipEntry(tooLong)) }
        assertEquals(0, baos.size())
    }

    @Test
    fun multiByteNameCountsBytes() {
        // 0x8000 characters, 0x10000 bytes as UTF-8.
        val zos = ZipOutputStream(ByteArrayOutputStream())
        assertFailsWith<IllegalArgumentException> { zos.putNextEntry(ZipEntry("ø".repeat(0x8000))) }
    }

    @Test
    fun entryCommentTooLongRejected() {
        val zos = ZipOutputStream(ByteArrayOutputStream())
        val entry = ZipEntry("a.txt")
        entry.comment = tooLong
        assertFailsWith<IllegalArgumentException> { zos.putNextEntry(entry) }
    }

    @Test
    fun entryCommentMadeTooLongAfterPutRejected() {
        val zos = ZipOutputStream(ByteArrayOutputStream())
        val entry = ZipEntry("a.txt")
        zos.putNextEntry(entry)
        entry.comment = tooLong
        assertFailsWith<IllegalStateException> { zos.finish() }
    }

    @Test
    fun archiveCommentTooLongRejected() {
        val zos = ZipOutputStream(ByteArrayOutputStream())
        assertFailsWith<IllegalArgumentException> { zos.setComment(tooLong) }
        zos.setComment("x".repeat(0xFFFF))
    }
}
