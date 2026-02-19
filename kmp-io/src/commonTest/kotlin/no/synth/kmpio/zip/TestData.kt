package no.synth.kmpio.zip

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
}
