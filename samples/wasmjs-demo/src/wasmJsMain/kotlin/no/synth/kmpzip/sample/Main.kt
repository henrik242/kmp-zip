@file:Suppress("UNUSED_PARAMETER")

package no.synth.kmpzip.sample

import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.readBytes
import no.synth.kmpzip.zip.ZipInputStream
import no.synth.kmpzip.zip.ZipPasswordException

// External binding for the JS-global Uint8Array — Kotlin/Wasm 2.3 stdlib
// doesn't expose it.
external class Uint8Array(size: Int) : JsAny {
    val length: Int
}

// Bulk Uint8Array → byte-identity String. NOT TextDecoder('latin1'): WHATWG
// Encoding aliases that label to windows-1252, which remaps 0x80..0x9F and
// breaks the byte-identity round-trip we need. `String.fromCharCode` is a
// pure code-point map. Chunked `apply` keeps allocation linear without
// blowing past JS engines' max-args-per-call limit.
private fun uint8ArrayToLatin1String(arr: Uint8Array): String =
    js("""(() => {
        const CHUNK = 16384;
        let result = '';
        for (let i = 0; i < arr.length; i += CHUNK) {
            result += String.fromCharCode.apply(null, arr.subarray(i, i + CHUNK));
        }
        return result;
    })()""")

private fun latin1StringToUint8Array(s: String, length: Int): Uint8Array =
    js("(() => { const a = new Uint8Array(length); for (let i = 0; i < length; i++) a[i] = s.charCodeAt(i); return a; })()")

internal fun Uint8Array.toByteArray(): ByteArray {
    val s = uint8ArrayToLatin1String(this)
    return ByteArray(s.length) { i -> s[i].code.toByte() }
}

internal fun ByteArray.toUint8Array(): Uint8Array {
    val chars = CharArray(size) { i -> (this[i].toInt() and 0xff).toChar() }
    return latin1StringToUint8Array(chars.concatToString(), size)
}

internal fun gunzip(input: Uint8Array): Uint8Array =
    GzipInputStream(ByteArrayInputStream(input.toByteArray()))
        .use { it.readBytes() }
        .toUint8Array()

// Walks every entry, reading bytes to prove the password works (and to grab a
// preview). Throws `ZipPasswordException` if the archive turns out to need a
// password — main() catches that and prompts the user.
internal fun listZipEntries(input: Uint8Array, password: String?): String = buildString {
    val pwBytes = password?.encodeToByteArray()
    append("name\tsize\tmethod\tpreview\n")
    ZipInputStream(ByteArrayInputStream(input.toByteArray()), pwBytes).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            val method = if (entry.method == 0) "stored" else "deflated"
            val tag = if (entry.isDirectory) "${entry.name} (dir)" else entry.name
            val preview = when {
                entry.isDirectory -> "-"
                else -> previewOf(zis.readBytes())
            }
            append(tag).append('\t').append(entry.size).append('\t').append(method).append('\t').append(preview).append('\n')
        }
    }
}

private fun previewOf(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "(empty)"
    val text = bytes.take(60).toByteArray().decodeToString()
    val printable = text.all { it.code in 9..126 || it in ' '..'ÿ' }
    if (!printable) return "<binary, ${bytes.size} bytes>"
    val flat = text.replace('\n', '↵').replace('\r', ' ').replace('\t', ' ')
    return if (bytes.size > 60) "$flat…" else flat
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

// Returns null when the user cancels. Standard browser prompt is enough for
// a sample — a real app would render a proper input field instead.
private fun jsPrompt(message: String): String? = js("prompt(message)")

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
        var password: String? = null
        while (true) {
            try {
                val t0 = nowMs()
                val listing = listZipEntries(bytes, password)
                val ms = ((nowMs() - t0) * 10).toInt() / 10.0
                val pwTag = if (password != null) " (decrypted)" else ""
                showResult(
                    "zip-output", "zip-meta",
                    listing,
                    "$fileName: ${bytes.length} bytes scanned in $ms ms$pwTag",
                )
                return@onFilePicked
            } catch (e: ZipPasswordException) {
                val prompt = if (password == null)
                    "Encrypted entry found in $fileName.\nEnter password:"
                else
                    "Wrong password. Try again:"
                password = jsPrompt(prompt)
                if (password.isNullOrEmpty()) {
                    showError("zip-output", "zip-meta", "Cancelled — password required for $fileName")
                    return@onFilePicked
                }
            } catch (e: Throwable) {
                showError("zip-output", "zip-meta", e.message ?: e.toString())
                return@onFilePicked
            }
        }
    }
}
