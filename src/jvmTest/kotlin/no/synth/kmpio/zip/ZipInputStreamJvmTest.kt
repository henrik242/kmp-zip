package no.synth.kmpio.zip

import kotlin.test.Test
import kotlin.test.assertEquals

class ZipInputStreamJvmTest {

    private fun readAllBytes(stream: java.io.InputStream): ByteArray {
        val buf = ByteArray(4096)
        val out = mutableListOf<Byte>()
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n == -1) break
            for (i in 0 until n) out.add(buf[i])
        }
        return out.toByteArray()
    }

    private data class EntryData(val name: String, val content: ByteArray, val method: Int, val isDirectory: Boolean)

    private fun readWithOurs(data: ByteArray): List<EntryData> {
        val zis = ZipInputStream(data)
        val entries = mutableListOf<EntryData>()
        while (true) {
            val entry = zis.nextEntry ?: break
            val content = readAllBytes(zis)
            entries.add(EntryData(entry.name, content, entry.method, entry.isDirectory))
        }
        zis.close()
        return entries
    }

    private fun readWithJava(data: ByteArray): List<EntryData> {
        val jis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(data))
        val entries = mutableListOf<EntryData>()
        while (true) {
            val entry = jis.nextEntry ?: break
            val content = readAllBytes(jis)
            entries.add(EntryData(entry.name, content, entry.method, entry.isDirectory))
        }
        jis.close()
        return entries
    }

    private fun assertIdentical(zipData: ByteArray, label: String) {
        val ours = readWithOurs(zipData)
        val java = readWithJava(zipData)

        assertEquals(java.size, ours.size, "$label: entry count mismatch")
        for (i in java.indices) {
            val j = java[i]
            val o = ours[i]
            assertEquals(j.name, o.name, "$label entry $i: name mismatch")
            assertEquals(j.method, o.method, "$label entry $i: method mismatch")
            assertEquals(j.isDirectory, o.isDirectory, "$label entry $i: isDirectory mismatch")
            assertEquals(j.content.size, o.content.size, "$label entry $i: content size mismatch")
            for (b in j.content.indices) {
                assertEquals(j.content[b], o.content[b], "$label entry $i: content byte $b mismatch")
            }
        }
    }

    @Test
    fun identicalStoredZip() = assertIdentical(TestData.storedZip, "storedZip")

    @Test
    fun identicalDeflatedZip() = assertIdentical(TestData.deflatedZip, "deflatedZip")

    @Test
    fun identicalMultiEntryZip() = assertIdentical(TestData.multiEntryZip, "multiEntryZip")

    @Test
    fun identicalDirectoryZip() = assertIdentical(TestData.directoryZip, "directoryZip")

    @Test
    fun identicalBinaryZip() = assertIdentical(TestData.binaryZip, "binaryZip")

    @Test
    fun identicalCliStoredZip() = assertIdentical(TestData.cliStoredZip, "cliStoredZip")

    @Test
    fun identicalCliDeflatedZip() = assertIdentical(TestData.cliDeflatedZip, "cliDeflatedZip")

    @Test
    fun identicalCliWithDirZip() = assertIdentical(TestData.cliWithDirZip, "cliWithDirZip")

    @Test
    fun identicalCliMixedZip() = assertIdentical(TestData.cliMixedZip, "cliMixedZip")

    @Test
    fun identicalSevenStoredZip() = assertIdentical(TestData.sevenStoredZip, "sevenStoredZip")

    @Test
    fun identicalSevenDeflatedZip() = assertIdentical(TestData.sevenDeflatedZip, "sevenDeflatedZip")

    @Test
    fun identicalEmptyZip() = assertIdentical(TestData.emptyZip, "emptyZip")
}
