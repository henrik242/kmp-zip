package no.synth.kmpio.zip

import no.synth.kmpio.io.InputStream

actual class ZipInputStream actual constructor(private val input: InputStream) : InputStream() {
    private var currentEntry: ZipEntry? = null
    private var closed = false
    private var entryEof = true

    // For STORED entries
    private var remainingBytes: Long = 0

    // For DEFLATED entries
    private var inflater: Inflater? = null
    private var inflaterBuf = ByteArray(512)
    private var inflaterBufPos = 0
    private var inflaterBufLen = 0

    // Pushback buffer for bytes read from input but not consumed by inflater
    private var pushbackBuf: ByteArray? = null
    private var pushbackPos = 0
    private var pushbackLen = 0

    // For tracking data descriptor needs
    private var hasDataDescriptor = false

    actual fun readBytes(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var totalSize = 0
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n == -1) break
            chunks.add(buffer.copyOf(n))
            totalSize += n
        }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    actual val nextEntry: ZipEntry?
        get() {
            if (closed) throw Exception("Stream closed")
            closeEntry()

            return try {
                readNextEntry()
            } catch (_: Exception) {
                null
            }
        }

    private fun readNextEntry(): ZipEntry? {
        val sig = readLeInt()
        if (sig != ZipConstants.LOCAL_FILE_HEADER_SIGNATURE) return null

        @Suppress("UNUSED_VARIABLE")
        val versionNeeded = readLeShort()
        val generalFlag = readLeShort()
        val method = readLeShort()
        val lastModTime = readLeShort()
        val lastModDate = readLeShort()
        val crc32 = readLeInt().toLong() and 0xFFFFFFFFL
        val compressedSize = readLeInt().toLong() and 0xFFFFFFFFL
        val uncompressedSize = readLeInt().toLong() and 0xFFFFFFFFL
        val nameLen = readLeShort()
        val extraLen = readLeShort()

        hasDataDescriptor = (generalFlag and 0x08) != 0

        val nameBytes = readExact(nameLen)
        val name = nameBytes.decodeToString()

        val extra = if (extraLen > 0) readExact(extraLen) else null

        val dosTime = (lastModDate.toLong() shl 16) or lastModTime.toLong()

        val entry = ZipEntry(
            name = name,
            size = if (hasDataDescriptor) -1L else uncompressedSize,
            compressedSize = if (hasDataDescriptor) -1L else compressedSize,
            crc = if (hasDataDescriptor) -1L else crc32,
            method = method,
            time = dosTime,
            extra = extra,
        )

        currentEntry = entry
        entryEof = false

        when (method) {
            ZipConstants.STORED -> {
                remainingBytes = if (hasDataDescriptor) Long.MAX_VALUE else compressedSize
            }
            ZipConstants.DEFLATED -> {
                inflater = Inflater().also { it.init() }
                inflaterBufPos = 0
                inflaterBufLen = 0
            }
            else -> throw Exception("Unsupported compression method: $method")
        }

        return entry
    }

    actual fun closeEntry() {
        if (entryEof) return
        val skipBuf = ByteArray(256)
        while (!entryEof) {
            if (read(skipBuf, 0, skipBuf.size) == -1) break
        }
        currentEntry = null
    }

    actual override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    actual override fun read(b: ByteArray, off: Int, len: Int): Int {
        val entry = currentEntry
        if (entryEof || entry == null) return -1
        return when (entry.method) {
            ZipConstants.STORED -> readStored(b, off, len)
            ZipConstants.DEFLATED -> readDeflated(b, off, len)
            else -> -1
        }
    }

    private fun readStored(b: ByteArray, off: Int, len: Int): Int {
        if (remainingBytes <= 0 && !hasDataDescriptor) {
            finishEntry()
            return -1
        }

        if (hasDataDescriptor) {
            val n = readRaw(b, off, len)
            if (n == -1) {
                finishEntry()
                return -1
            }
            return n
        }

        val toRead = minOf(len.toLong(), remainingBytes).toInt()
        val n = readRaw(b, off, toRead)
        if (n == -1) {
            finishEntry()
            return -1
        }
        remainingBytes -= n
        if (remainingBytes <= 0) {
            finishEntry()
        }
        return n
    }

    private fun readDeflated(b: ByteArray, off: Int, len: Int): Int {
        val inf = inflater ?: return -1
        if (inf.isFinished) {
            finishEntry()
            return -1
        }

        while (true) {
            if (inflaterBufLen > inflaterBufPos) {
                val result = inf.inflate(
                    inflaterBuf, inflaterBufPos, inflaterBufLen - inflaterBufPos,
                    b, off, len
                )
                inflaterBufPos += result.bytesConsumed
                if (result.bytesProduced > 0) {
                    if (result.streamEnd) finishEntry()
                    return result.bytesProduced
                }
                if (result.streamEnd) {
                    finishEntry()
                    return -1
                }
            }

            val n = readRaw(inflaterBuf, 0, inflaterBuf.size)
            if (n == -1) {
                finishEntry()
                return -1
            }
            inflaterBufPos = 0
            inflaterBufLen = n
        }
    }

    private fun finishEntry() {
        if (entryEof) return
        entryEof = true

        // Save unconsumed inflater buffer bytes to pushback
        if (inflater != null && inflaterBufPos < inflaterBufLen) {
            val remaining = inflaterBufLen - inflaterBufPos
            pushbackBuf = inflaterBuf.copyOfRange(inflaterBufPos, inflaterBufLen)
            pushbackPos = 0
            pushbackLen = remaining
        }

        inflater?.end()
        inflater = null

        if (hasDataDescriptor) {
            readDataDescriptor()
        }
    }

    private fun readDataDescriptor() {
        val possibleSig = readLeInt()
        if (possibleSig == ZipConstants.DATA_DESCRIPTOR_SIGNATURE) {
            val crc = readLeInt().toLong() and 0xFFFFFFFFL
            val compressedSize = readLeInt().toLong() and 0xFFFFFFFFL
            val size = readLeInt().toLong() and 0xFFFFFFFFL
            currentEntry?.let {
                it.crc = crc
                it.compressedSize = compressedSize
                it.size = size
            }
        } else {
            val crc = possibleSig.toLong() and 0xFFFFFFFFL
            val compressedSize = readLeInt().toLong() and 0xFFFFFFFFL
            val size = readLeInt().toLong() and 0xFFFFFFFFL
            currentEntry?.let {
                it.crc = crc
                it.compressedSize = compressedSize
                it.size = size
            }
        }
    }

    actual override fun available(): Int {
        return if (entryEof || currentEntry == null) 0 else 1
    }

    actual override fun close() {
        if (!closed) {
            closed = true
            inflater?.end()
            inflater = null
            input.close()
        }
    }

    // -- Low-level reading helpers that respect pushback buffer --

    private fun readRaw(b: ByteArray, off: Int, len: Int): Int {
        val pb = pushbackBuf
        if (pb != null && pushbackPos < pushbackLen) {
            val available = pushbackLen - pushbackPos
            val toRead = minOf(len, available)
            pb.copyInto(b, off, pushbackPos, pushbackPos + toRead)
            pushbackPos += toRead
            if (pushbackPos >= pushbackLen) {
                pushbackBuf = null
                pushbackPos = 0
                pushbackLen = 0
            }
            return toRead
        }
        return input.read(b, off, len)
    }

    private fun readRawByte(): Int {
        val pb = pushbackBuf
        if (pb != null && pushbackPos < pushbackLen) {
            val b = pb[pushbackPos++].toInt() and 0xFF
            if (pushbackPos >= pushbackLen) {
                pushbackBuf = null
                pushbackPos = 0
                pushbackLen = 0
            }
            return b
        }
        return input.read()
    }

    private fun readLeShort(): Int {
        val b0 = readByte()
        val b1 = readByte()
        return (b1 shl 8) or b0
    }

    private fun readLeInt(): Int {
        val b0 = readByte()
        val b1 = readByte()
        val b2 = readByte()
        val b3 = readByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun readByte(): Int {
        val b = readRawByte()
        if (b == -1) throw Exception("Unexpected end of ZIP stream")
        return b
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = readRaw(buf, offset, n - offset)
            if (read == -1) throw Exception("Unexpected end of ZIP stream")
            offset += read
        }
        return buf
    }
}
