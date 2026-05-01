@file:Suppress("UNUSED_PARAMETER")

package no.synth.kmpzip.sample

import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.readBytes
import no.synth.kmpzip.zip.ZipInputStream

// External binding for the JS-global Uint8Array — Kotlin/Wasm 2.3 stdlib
// doesn't expose it.
external class Uint8Array(size: Int) : JsAny {
    val length: Int
}

// Latin-1 round-trip moves bytes across the wasm/JS boundary in two bulk
// String marshals instead of N per-byte calls. Bytes 0..255 map to chars 0..255
// cleanly through the latin1 codec.
private fun uint8ArrayToLatin1String(arr: Uint8Array): String =
    js("(new TextDecoder('latin1')).decode(arr)")

private fun latin1StringToUint8Array(s: String, length: Int): Uint8Array =
    js("(() => { const a = new Uint8Array(length); for (let i = 0; i < length; i++) a[i] = s.charCodeAt(i); return a; })()")

private fun Uint8Array.toByteArray(): ByteArray {
    val s = uint8ArrayToLatin1String(this)
    return ByteArray(s.length) { i -> s[i].code.toByte() }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val chars = CharArray(size) { i -> (this[i].toInt() and 0xff).toChar() }
    return latin1StringToUint8Array(chars.concatToString(), size)
}

private fun gunzip(input: Uint8Array): Uint8Array =
    GzipInputStream(ByteArrayInputStream(input.toByteArray()))
        .use { it.readBytes() }
        .toUint8Array()

private fun listZipEntries(input: Uint8Array): String = buildString {
    append("name\tsize\tmethod\n")
    ZipInputStream(ByteArrayInputStream(input.toByteArray())).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            val method = if (entry.method == 0) "stored" else "deflated"
            val tag = if (entry.isDirectory) "${entry.name} (dir)" else entry.name
            append(tag).append('\t').append(entry.size).append('\t').append(method).append('\n')
        }
    }
}

// DOM glue. Each helper is a one-liner over the JS API; keeping them named
// makes the Kotlin code below read like normal Kotlin.
private fun setText(elementId: String, text: String): Unit =
    js("document.getElementById(elementId).textContent = text")

private fun addClass(elementId: String, cls: String): Unit =
    js("document.getElementById(elementId).classList.add(cls)")

private fun removeClass(elementId: String, cls: String): Unit =
    js("document.getElementById(elementId).classList.remove(cls)")

private fun decodeUtf8(bytes: Uint8Array): String =
    js("(new TextDecoder()).decode(bytes)")

private fun nowMs(): Double = js("performance.now()")

private fun onFilePicked(elementId: String, handler: (String, Uint8Array) -> Unit) {
    js(
        """
        document.getElementById(elementId).addEventListener('change', async (ev) => {
            const file = ev.target.files && ev.target.files[0];
            if (!file) return;
            const bytes = new Uint8Array(await file.arrayBuffer());
            handler(file.name, bytes);
        })
        """
    )
}

private fun showResult(outputId: String, metaId: String, text: String, meta: String) {
    setText(outputId, text)
    removeClass(outputId, "err")
    setText(metaId, meta)
}

private fun showError(outputId: String, metaId: String, message: String) {
    setText(outputId, message)
    addClass(outputId, "err")
    setText(metaId, "")
}

fun main() {
    onFilePicked("gz-file") { fileName, bytes ->
        try {
            val t0 = nowMs()
            val out = gunzip(bytes)
            val ms = ((nowMs() - t0) * 10).toInt() / 10.0
            showResult(
                "gz-output", "gz-meta",
                decodeUtf8(out),
                "$fileName: ${bytes.length} → ${out.length} bytes in $ms ms",
            )
        } catch (e: Throwable) {
            showError("gz-output", "gz-meta", e.message ?: e.toString())
        }
    }

    onFilePicked("zip-file") { fileName, bytes ->
        try {
            val t0 = nowMs()
            val listing = listZipEntries(bytes)
            val ms = ((nowMs() - t0) * 10).toInt() / 10.0
            showResult(
                "zip-output", "zip-meta",
                listing,
                "$fileName: ${bytes.length} bytes scanned in $ms ms",
            )
        } catch (e: Throwable) {
            showError("zip-output", "zip-meta", e.message ?: e.toString())
        }
    }
}
