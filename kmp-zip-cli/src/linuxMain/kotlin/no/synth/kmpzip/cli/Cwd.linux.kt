package no.synth.kmpzip.cli

// Duplicated in macosMain and mingwMain because `getcwd`'s `size_t` parameter has
// divergent bit widths across LP64 (Apple/Linux) and LLP64 (mingw). The metadata
// compiler refuses a shared `nativeMain` actual even with kotlinx.cinterop.convert();
// the same dynamic forces three per-target actuals for `zlibCrc32Update` in :kmp-zip.

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getcwd

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentWorkingDirectory(): String? = memScoped {
    val buf = allocArray<ByteVar>(4096)
    getcwd(buf, 4096.convert())?.toKString()
}
