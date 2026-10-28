package no.synth.kmpzip.kotlinx

import kotlinx.io.Buffer
import no.synth.kmpzip.deflate.DeflateFormat
import no.synth.kmpzip.io.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class DeflateAdapterTest {

    @Test
    fun roundTripsThroughBuffer() {
        for (format in DeflateFormat.entries) {
            val buffer = Buffer()
            DeflateOutputStream(buffer, format).use { it.write("Hello, deflate!".encodeToByteArray()) }
            val text = InflateInputStream(buffer, format).use { it.readBytes().decodeToString() }
            assertEquals("Hello, deflate!", text, "$format")
        }
    }
}
