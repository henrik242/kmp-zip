package no.synth.kmpzip.zip

private val DRIVE_LETTER_REGEX = Regex("^[A-Za-z]:")

/**
 * Splits a ZIP entry name into safe path segments rooted at a target directory.
 * Rejects empty names, control chars (other than tab), absolute paths, drive
 * letters, and parent-dir traversal (`..`). Trims redundant `.` segments.
 *
 * Returns the cleaned segments; callers join them onto their own [Path] type
 * (okio.Path / kotlinx.io.files.Path / java.nio.file.Path).
 *
 * @throws IllegalArgumentException if the entry name is unsafe or malformed.
 */
fun safeEntrySegments(rawName: String): List<String> {
    require(rawName.isNotEmpty()) { "Empty entry name not allowed" }
    require(rawName.none { it.code < 0x20 && it != '\t' }) {
        "Entry name contains control character: $rawName"
    }
    require(!rawName.startsWith("/") && !rawName.startsWith("\\")) {
        "Absolute entry path not allowed: $rawName"
    }
    require(!DRIVE_LETTER_REGEX.containsMatchIn(rawName)) {
        "Drive-letter entry path not allowed: $rawName"
    }
    val segments = rawName.split('/', '\\').filter { it.isNotEmpty() && it != "." }
    require(segments.none { it == ".." }) { "Entry path escapes target dir: $rawName" }
    return segments
}
