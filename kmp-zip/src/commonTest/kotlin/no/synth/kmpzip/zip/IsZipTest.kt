package no.synth.kmpzip.zip

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsZipTest {

    @Test
    fun recognizesOrdinaryArchives() {
        assertTrue(isZip(TestData.storedZip))
        // AES archives still start with a plain local header
        assertTrue(isZip(TestData.aes256DeflatedZip))
    }

    @Test
    fun recognizesAZeroEntryArchive() {
        assertTrue(isZip(TestData.emptyZip))
        // the bare EOCD signature is the smallest true input
        assertTrue(isZip(byteArrayOf(0x50, 0x4b, 0x05, 0x06)))
    }

    @Test
    fun sniffsRatherThanValidates() {
        assertTrue(isZip(TestData.multiEntryZip.copyOfRange(0, 10)))
    }

    @Test
    fun rejectsNonZipBytes() {
        assertFalse(isZip(ByteArray(0)))
        assertFalse(isZip(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        assertFalse(isZip(byteArrayOf(0x50, 0x4b, 0x05)))
        assertFalse(isZip("PK is also how a text can start".encodeToByteArray()))
        assertFalse(isZip(TestData.cliGzip))
    }

    @Test
    fun rejectsOtherLeadingPkSignatures() {
        // central dir, data descriptor, PK00 spanned marker, ZIP64 EOCD
        assertFalse(isZip(byteArrayOf(0x50, 0x4b, 0x01, 0x02)))
        assertFalse(isZip(byteArrayOf(0x50, 0x4b, 0x07, 0x08)))
        assertFalse(isZip(byteArrayOf(0x50, 0x4b, 0x30, 0x30)))
        assertFalse(isZip(byteArrayOf(0x50, 0x4b, 0x06, 0x06)))
    }
}
