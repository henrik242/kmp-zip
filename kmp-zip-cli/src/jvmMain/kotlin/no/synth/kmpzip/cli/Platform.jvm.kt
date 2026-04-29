package no.synth.kmpzip.cli

internal actual fun printErr(message: String) {
    System.err.println(message)
}

internal actual fun currentWorkingDirectory(): String? = System.getProperty("user.dir")

internal actual fun exitProcess(code: Int): Nothing = kotlin.system.exitProcess(code)
