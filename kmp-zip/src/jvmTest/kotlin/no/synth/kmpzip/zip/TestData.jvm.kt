package no.synth.kmpzip.zip

actual object TestData {
    actual fun loadResource(name: String): ByteArray {
        return TestData::class.java.getResourceAsStream("/testdata/$name")?.readAllBytes()
            ?: error("Resource not found: testdata/$name")
    }

    actual val storedZip: ByteArray get() = loadResource("stored.zip")
    actual val deflatedZip: ByteArray get() = loadResource("deflated.zip")
    actual val multiEntryZip: ByteArray get() = loadResource("multi-entry.zip")
    actual val directoryZip: ByteArray get() = loadResource("directory.zip")
    actual val emptyZip: ByteArray get() = loadResource("empty.zip")
    actual val binaryZip: ByteArray get() = loadResource("binary.zip")
    actual val cliStoredZip: ByteArray get() = loadResource("cli-stored.zip")
    actual val cliDeflatedZip: ByteArray get() = loadResource("cli-deflated.zip")
    actual val cliWithDirZip: ByteArray get() = loadResource("cli-with-dir.zip")
    actual val cliMixedZip: ByteArray get() = loadResource("cli-mixed.zip")
    actual val sevenStoredZip: ByteArray get() = loadResource("seven-stored.zip")
    actual val sevenDeflatedZip: ByteArray get() = loadResource("seven-deflated.zip")
    actual val cliGzip: ByteArray get() = loadResource("cli.gz")

    // AES-encrypted test ZIPs
    actual val aes128DeflatedZip: ByteArray get() = loadResource("aes128-deflated.zip")
    actual val aes256BinaryZip: ByteArray get() = loadResource("aes256-binary.zip")
    actual val aes256DeflatedZip: ByteArray get() = loadResource("aes256-deflated.zip")
    actual val aes256MultiZip: ByteArray get() = loadResource("aes256-multi.zip")
    actual val aes256StoredZip: ByteArray get() = loadResource("aes256-stored.zip")

    // Legacy (ZipCrypto) encrypted test ZIPs
    actual val legacyStoredZip: ByteArray get() = loadResource("legacy-stored.zip")
    actual val legacyDeflatedZip: ByteArray get() = loadResource("legacy-deflated.zip")
    actual val legacyBinaryZip: ByteArray get() = loadResource("legacy-binary.zip")
    actual val legacyMultiZip: ByteArray get() = loadResource("legacy-multi.zip")
}
