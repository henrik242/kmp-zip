package no.synth.kmpzip.zip

expect object TestData {
    fun loadResource(name: String): ByteArray

    val storedZip: ByteArray
    val deflatedZip: ByteArray
    val multiEntryZip: ByteArray
    val directoryZip: ByteArray
    val emptyZip: ByteArray
    val binaryZip: ByteArray
    val cliStoredZip: ByteArray
    val cliDeflatedZip: ByteArray
    val cliWithDirZip: ByteArray
    val cliMixedZip: ByteArray
    val sevenStoredZip: ByteArray
    val sevenDeflatedZip: ByteArray
    val cliGzip: ByteArray

    // AES-encrypted test ZIPs (password: "password")
    val aes128DeflatedZip: ByteArray
    val aes256BinaryZip: ByteArray
    val aes256DeflatedZip: ByteArray
    val aes256MultiZip: ByteArray
    val aes256StoredZip: ByteArray

    // Legacy (ZipCrypto) encrypted test ZIPs (password: "password")
    val legacyStoredZip: ByteArray
    val legacyDeflatedZip: ByteArray
    val legacyBinaryZip: ByteArray
    val legacyMultiZip: ByteArray
}
