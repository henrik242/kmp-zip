package no.synth.kmpzip.zip

/** General purpose bit 11: the entry's name and comment are UTF-8 (APPNOTE 4.4.4). */
internal const val UTF8_FLAG = 0x0800

/**
 * Decodes an entry name or comment. With [utf8Flag] set the bytes are UTF-8. Without
 * it the spec says CP437, but many tools write UTF-8 and leave the flag unset, so
 * bytes that are valid UTF-8 are read as UTF-8 and only the rest as CP437.
 */
internal fun decodeZipText(bytes: ByteArray, start: Int, end: Int, utf8Flag: Boolean): String {
    if (utf8Flag) return bytes.decodeToString(start, end)
    return try {
        bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        decodeCp437(bytes, start, end)
    }
}

/** True if [text] needs [UTF8_FLAG]: it has characters outside ASCII. */
internal fun needsUtf8Flag(text: String?): Boolean = text != null && text.any { it.code > 0x7F }

private fun decodeCp437(bytes: ByteArray, start: Int, end: Int): String {
    val sb = StringBuilder(end - start)
    for (i in start until end) {
        val b = bytes[i].toInt() and 0xFF
        sb.append(if (b < 0x80) b.toChar() else CP437_HIGH[b - 0x80])
    }
    return sb.toString()
}

// CP437 0x80-0xFF. The low half is ASCII.
private const val CP437_HIGH =
    "ÇüéâäàåçêëèïîìÄÅ" +
        "ÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
        "áíóúñÑªº¿⌐¬½¼¡«»" +
        "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
        "└┴┬├─┼╞╟╚╔╩╦╠═╬╧" +
        "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
        "αßΓπΣσµτΦΘΩδ∞φε∩" +
        "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "
