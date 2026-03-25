package no.synth.kmpzip.zip

import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayOutputStream

/**
 * Generates test zip files using Zip4j for cross-library interop testing.
 * Zip4j uses data descriptors (flag bit 3) with compressedSize=0 in the
 * local header, which exercises the data descriptor resolution code path.
 */
object Zip4jTestDataGenerator {

    const val PASSWORD = "testpass"

    private fun aesParams(name: String, method: CompressionMethod = CompressionMethod.DEFLATE): ZipParameters {
        return ZipParameters().apply {
            fileNameInZip = name
            compressionMethod = method
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            isEncryptFiles = true
        }
    }

    /** Single deflated entry. */
    fun singleDeflated(): ByteArray {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, PASSWORD.toCharArray()).use { zos ->
            zos.putNextEntry(aesParams("hello.txt"))
            zos.write("Hello from Zip4j!".toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Two deflated entries — exercises multi-entry pushback handling. */
    fun multiDeflated(): ByteArray {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, PASSWORD.toCharArray()).use { zos ->
            zos.putNextEntry(aesParams("first.txt"))
            zos.write("First Zip4j entry".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(aesParams("second.txt"))
            zos.write("Second Zip4j entry".toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Single stored (uncompressed) entry. */
    fun singleStored(): ByteArray {
        val content = "Stored Zip4j content".toByteArray()
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, PASSWORD.toCharArray()).use { zos ->
            val params = aesParams("stored.txt", CompressionMethod.STORE)
            params.entrySize = content.size.toLong()
            zos.putNextEntry(params)
            zos.write(content)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    // ---- Legacy (ZipCrypto) encryption ----

    private fun legacyParams(name: String, method: CompressionMethod = CompressionMethod.DEFLATE): ZipParameters {
        return ZipParameters().apply {
            fileNameInZip = name
            compressionMethod = method
            encryptionMethod = EncryptionMethod.ZIP_STANDARD
            isEncryptFiles = true
        }
    }

    /** Single legacy-encrypted deflated entry. */
    fun legacyDeflated(): ByteArray {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, PASSWORD.toCharArray()).use { zos ->
            zos.putNextEntry(legacyParams("legacy.txt"))
            zos.write("Hello from Zip4j legacy!".toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Two legacy-encrypted deflated entries. */
    fun legacyMultiDeflated(): ByteArray {
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, PASSWORD.toCharArray()).use { zos ->
            zos.putNextEntry(legacyParams("first.txt"))
            zos.write("First legacy entry".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(legacyParams("second.txt"))
            zos.write("Second legacy entry".toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Single legacy-encrypted stored entry. */
    fun legacyStored(): ByteArray {
        val content = "Stored legacy content".toByteArray()
        val baos = ByteArrayOutputStream()
        Zip4jOutputStream(baos, PASSWORD.toCharArray()).use { zos ->
            val params = legacyParams("stored.txt", CompressionMethod.STORE)
            params.entrySize = content.size.toLong()
            zos.putNextEntry(params)
            zos.write(content)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
