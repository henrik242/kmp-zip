package no.synth.kmpzip.zip

import no.synth.kmpzip.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ZipNameEncodingTest {

    private fun createZip(name: String, comment: String? = null): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entry = ZipEntry(name)
            entry.comment = comment
            zos.putNextEntry(entry)
            zos.write("data".encodeToByteArray())
        }
        return baos.toByteArray()
    }

    private fun centralDirectoryOffset(data: ByteArray) = readLeUInt(data, data.size - 6).toInt()

    private fun localFlag(data: ByteArray) = readLeShort(data, 6)

    private fun centralFlag(data: ByteArray) = readLeShort(data, centralDirectoryOffset(data) + 8)

    /** Clears or sets bit 11 in both headers of a single-entry archive. */
    private fun setUtf8Flag(data: ByteArray, on: Boolean) {
        for (at in listOf(6, centralDirectoryOffset(data) + 8)) {
            val flag = readLeShort(data, at)
            val updated = if (on) flag or UTF8_FLAG else flag and UTF8_FLAG.inv()
            data[at] = (updated and 0xFF).toByte()
            data[at + 1] = (updated ushr 8).toByte()
        }
    }

    /** Overwrites the name bytes in both headers of a single-entry archive. */
    private fun replaceName(data: ByteArray, name: ByteArray) {
        val cd = centralDirectoryOffset(data)
        assertEquals(name.size, readLeShort(data, 26))
        name.copyInto(data, 30)
        name.copyInto(data, cd + 46)
    }

    private fun namesRead(data: ByteArray): Pair<String, String> {
        val streamed = ZipInputStream(data).use { assertNotNull(it.nextEntry).name }
        val central = ZipFile(data).use { it.entries.single().name }
        return streamed to central
    }

    @Test
    fun nonAsciiNameSetsUtf8Flag() {
        val data = createZip("blåbær.txt")
        assertEquals(UTF8_FLAG, localFlag(data) and UTF8_FLAG)
        assertEquals(UTF8_FLAG, centralFlag(data) and UTF8_FLAG)
        assertEquals("blåbær.txt" to "blåbær.txt", namesRead(data))
    }

    @Test
    fun nonAsciiCommentSetsUtf8Flag() {
        val data = createZip("a.txt", comment = "smørbrød")
        assertEquals(UTF8_FLAG, centralFlag(data) and UTF8_FLAG)
        assertEquals("smørbrød", ZipFile(data).use { it.entries.single().comment })
    }

    @Test
    fun asciiNameSetsUtf8FlagToo() {
        val data = createZip("plain.txt", comment = "plain")
        assertEquals(UTF8_FLAG, localFlag(data) and UTF8_FLAG)
        assertEquals(UTF8_FLAG, centralFlag(data) and UTF8_FLAG)
    }

    @Test
    fun commentSetAfterPutNextEntryKeepsHeadersConsistent() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entry = ZipEntry("a.txt")
            zos.putNextEntry(entry)
            entry.comment = "smørbrød"
        }
        val data = baos.toByteArray()
        assertEquals(localFlag(data), centralFlag(data))
        assertEquals("smørbrød", ZipFile(data).use { it.entries.single().comment })
    }

    @Test
    fun utf8WithoutFlagIsReadAsUtf8() {
        val data = createZip("blåbær.txt")
        setUtf8Flag(data, on = false)
        assertEquals("blåbær.txt" to "blåbær.txt", namesRead(data))
    }

    @Test
    fun cp437WithoutFlagIsReadAsCp437() {
        val data = createZip("blXbXr.txt")
        // "blåbær.txt" in CP437: å = 0x86, æ = 0x91. Not valid UTF-8.
        val cp437 = "blXbXr.txt".encodeToByteArray()
        cp437[2] = 0x86.toByte()
        cp437[4] = 0x91.toByte()
        replaceName(data, cp437)
        setUtf8Flag(data, on = false)
        assertEquals("blåbær.txt" to "blåbær.txt", namesRead(data))
    }

    @Test
    fun flagForcesUtf8EvenForInvalidBytes() {
        val data = createZip("blXbXr.txt")
        val bytes = "blXbXr.txt".encodeToByteArray()
        bytes[2] = 0x86.toByte()
        replaceName(data, bytes)
        setUtf8Flag(data, on = true)
        // Invalid UTF-8 under the flag decodes with replacement characters, not CP437.
        assertEquals("bl�bXr.txt" to "bl�bXr.txt", namesRead(data))
    }
}
