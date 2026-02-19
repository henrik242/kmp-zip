package no.synth.kmpio.io

expect open class ByteArrayOutputStream : OutputStream {
    constructor()
    constructor(size: Int)

    override fun write(b: Int)
    override fun write(b: ByteArray, off: Int, len: Int)
    fun reset()
    fun size(): Int
    fun toByteArray(): ByteArray
    fun writeTo(out: OutputStream)
    override fun close()
}
