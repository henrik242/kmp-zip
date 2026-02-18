package no.synth.kmpio.zip

import kotlinx.cinterop.*
import no.synth.kmpio.io.OutputStream
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.crc32

@OptIn(ExperimentalForeignApi::class)
actual class ZipOutputStream actual constructor(private val output: OutputStream) : OutputStream() {
    private var closed = false
    private var finished = false
    private var currentEntry: ZipEntry? = null
    private var defaultMethod = ZipConstants.DEFLATED
    private var level = Z_DEFAULT_COMPRESSION
    private var comment: String? = null

    // Track entries for central directory
    private val entries = mutableListOf<EntryInfo>()
    private var bytesWritten: Long = 0

    // Per-entry state
    private var entryCrc: ULong = 0u
    private var entryUncompressedSize: Long = 0
    private var entryCompressedSize: Long = 0
    private var entryOffset: Long = 0
    private var deflater: Deflater? = null
    private val deflateBuf = ByteArray(8192)

    private class EntryInfo(
        val entry: ZipEntry,
        val offset: Long,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val flag: Int,
    )

    actual fun setComment(comment: String?) {
        this.comment = comment
    }

    actual fun setMethod(method: Int) {
        require(method == ZipConstants.STORED || method == ZipConstants.DEFLATED) {
            "Unsupported compression method: $method"
        }
        defaultMethod = method
    }

    actual fun setLevel(level: Int) {
        require(level in -1..9) { "Invalid compression level: $level" }
        this.level = level
    }

    actual fun putNextEntry(entry: ZipEntry) {
        if (closed) throw Exception("Stream closed")
        if (currentEntry != null) closeEntry()

        val method = if (entry.method == -1) defaultMethod else entry.method
        entry.method = method

        entryOffset = bytesWritten
        entryCrc = crc32(0u, null, 0u)
        entryUncompressedSize = 0
        entryCompressedSize = 0

        val flag = when (method) {
            ZipConstants.STORED -> {
                // For STORED, sizes and CRC must be known upfront
                if (entry.size == -1L || entry.crc == -1L) {
                    throw Exception("STORED entry requires size and crc to be set")
                }
                0
            }
            ZipConstants.DEFLATED -> {
                deflater = Deflater().also { it.init(level) }
                0x08 // data descriptor flag
            }
            else -> throw Exception("Unsupported compression method: $method")
        }

        writeLocalFileHeader(entry, method, flag)
        currentEntry = entry
    }

    private fun writeLocalFileHeader(entry: ZipEntry, method: Int, flag: Int) {
        val nameBytes = entry.name.encodeToByteArray()
        val extra = entry.extra

        writeLeInt(ZipConstants.LOCAL_FILE_HEADER_SIGNATURE)
        writeLeShort(20) // version needed to extract
        writeLeShort(flag) // general purpose bit flag
        writeLeShort(method) // compression method
        writeLeShort((entry.time and 0xFFFF).toInt()) // last mod file time
        writeLeShort(((entry.time ushr 16) and 0xFFFF).toInt()) // last mod file date
        if (flag and 0x08 != 0) {
            // Data descriptor follows, write zeros
            writeLeInt(0) // crc-32
            writeLeInt(0) // compressed size
            writeLeInt(0) // uncompressed size
        } else {
            writeLeInt(entry.crc.toInt()) // crc-32
            writeLeInt(entry.size.toInt()) // compressed size = uncompressed for STORED
            writeLeInt(entry.size.toInt()) // uncompressed size
        }
        writeLeShort(nameBytes.size) // file name length
        writeLeShort(extra?.size ?: 0) // extra field length
        writeRaw(nameBytes, 0, nameBytes.size)
        if (extra != null) {
            writeRaw(extra, 0, extra.size)
        }
    }

    actual override fun write(b: Int) {
        val buf = byteArrayOf(b.toByte())
        write(buf, 0, 1)
    }

    actual override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw Exception("Stream closed")
        val entry = currentEntry ?: throw Exception("No current entry")
        if (len == 0) return

        updateCrc(b, off, len)
        entryUncompressedSize += len

        when (entry.method) {
            ZipConstants.STORED -> writeStored(b, off, len)
            ZipConstants.DEFLATED -> writeDeflated(b, off, len)
        }
    }

    private fun writeStored(b: ByteArray, off: Int, len: Int) {
        writeRaw(b, off, len)
        entryCompressedSize += len
    }

    private fun writeDeflated(b: ByteArray, off: Int, len: Int) {
        val def = deflater ?: return
        var inputOffset = off
        var remaining = len

        while (remaining > 0) {
            val result = def.deflate(
                b, inputOffset, remaining,
                deflateBuf, 0, deflateBuf.size,
                Z_NO_FLUSH,
            )
            inputOffset += result.bytesConsumed
            remaining -= result.bytesConsumed

            if (result.bytesProduced > 0) {
                writeRaw(deflateBuf, 0, result.bytesProduced)
                entryCompressedSize += result.bytesProduced
            }
        }
    }

    actual fun closeEntry() {
        val entry = currentEntry ?: return

        if (entry.method == ZipConstants.DEFLATED) {
            finishDeflate()
        }

        val crc = entryCrc.toLong() and 0xFFFFFFFFL
        val flag = if (entry.method == ZipConstants.DEFLATED) 0x08 else 0

        entries.add(
            EntryInfo(
                entry = entry,
                offset = entryOffset,
                crc = crc,
                compressedSize = entryCompressedSize,
                uncompressedSize = entryUncompressedSize,
                flag = flag,
            )
        )

        if (flag and 0x08 != 0) {
            // Write data descriptor
            writeLeInt(ZipConstants.DATA_DESCRIPTOR_SIGNATURE)
            writeLeInt(crc.toInt())
            writeLeInt(entryCompressedSize.toInt())
            writeLeInt(entryUncompressedSize.toInt())
        }

        deflater?.end()
        deflater = null
        currentEntry = null
    }

    private fun finishDeflate() {
        val def = deflater ?: return
        val emptyInput = ByteArray(0)

        while (!def.isFinished) {
            val result = def.deflate(
                emptyInput, 0, 0,
                deflateBuf, 0, deflateBuf.size,
                Z_FINISH,
            )
            if (result.bytesProduced > 0) {
                writeRaw(deflateBuf, 0, result.bytesProduced)
                entryCompressedSize += result.bytesProduced
            }
            if (result.streamEnd) break
        }
    }

    actual fun finish() {
        if (finished) return
        if (closed) throw Exception("Stream closed")
        if (currentEntry != null) closeEntry()
        finished = true

        val centralDirOffset = bytesWritten

        // Write central directory
        for (info in entries) {
            writeCentralDirectoryHeader(info)
        }

        val centralDirSize = bytesWritten - centralDirOffset

        // Write end of central directory record
        writeEndOfCentralDirectory(centralDirOffset, centralDirSize)
    }

    private fun writeCentralDirectoryHeader(info: EntryInfo) {
        val entry = info.entry
        val nameBytes = entry.name.encodeToByteArray()
        val extra = entry.extra
        val commentBytes = entry.comment?.encodeToByteArray()

        writeLeInt(ZipConstants.CENTRAL_DIR_HEADER_SIGNATURE)
        writeLeShort(20) // version made by
        writeLeShort(20) // version needed to extract
        writeLeShort(info.flag) // general purpose bit flag
        writeLeShort(entry.method) // compression method
        writeLeShort((entry.time and 0xFFFF).toInt()) // last mod file time
        writeLeShort(((entry.time ushr 16) and 0xFFFF).toInt()) // last mod file date
        writeLeInt(info.crc.toInt()) // crc-32
        writeLeInt(info.compressedSize.toInt()) // compressed size
        writeLeInt(info.uncompressedSize.toInt()) // uncompressed size
        writeLeShort(nameBytes.size) // file name length
        writeLeShort(extra?.size ?: 0) // extra field length
        writeLeShort(commentBytes?.size ?: 0) // file comment length
        writeLeShort(0) // disk number start
        writeLeShort(0) // internal file attributes
        writeLeInt(0) // external file attributes
        writeLeInt(info.offset.toInt()) // relative offset of local header
        writeRaw(nameBytes, 0, nameBytes.size)
        if (extra != null) {
            writeRaw(extra, 0, extra.size)
        }
        if (commentBytes != null) {
            writeRaw(commentBytes, 0, commentBytes.size)
        }
    }

    private fun writeEndOfCentralDirectory(centralDirOffset: Long, centralDirSize: Long) {
        val commentBytes = comment?.encodeToByteArray()

        writeLeInt(ZipConstants.END_OF_CENTRAL_DIR_SIGNATURE)
        writeLeShort(0) // number of this disk
        writeLeShort(0) // disk where central directory starts
        writeLeShort(entries.size) // number of central directory records on this disk
        writeLeShort(entries.size) // total number of central directory records
        writeLeInt(centralDirSize.toInt()) // size of central directory
        writeLeInt(centralDirOffset.toInt()) // offset of start of central directory
        writeLeShort(commentBytes?.size ?: 0) // comment length
        if (commentBytes != null) {
            writeRaw(commentBytes, 0, commentBytes.size)
        }
    }

    actual override fun close() {
        if (!closed) {
            if (!finished) finish()
            closed = true
            deflater?.end()
            deflater = null
            output.close()
        }
    }

    // -- Low-level writing helpers --

    private fun updateCrc(data: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        data.usePinned { pinned ->
            entryCrc = crc32(entryCrc, (pinned.addressOf(off) as CPointer<UByteVar>), len.toUInt())
        }
    }

    private fun writeRaw(b: ByteArray, off: Int, len: Int) {
        output.write(b, off, len)
        bytesWritten += len
    }

    private fun writeLeShort(v: Int) {
        val buf = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
        )
        writeRaw(buf, 0, 2)
    }

    private fun writeLeInt(v: Int) {
        val buf = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
        writeRaw(buf, 0, 4)
    }
}
