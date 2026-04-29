package no.synth.kmpzip.cli

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
internal actual fun printErr(message: String) {
    fprintf(stderr, "%s\n", message)
    fflush(stderr)
}

internal actual fun exitProcess(code: Int): Nothing = kotlin.system.exitProcess(code)
