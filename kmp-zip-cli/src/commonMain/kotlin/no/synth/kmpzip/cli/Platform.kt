package no.synth.kmpzip.cli

internal expect fun printErr(message: String)

internal expect fun currentWorkingDirectory(): String?

internal expect fun exitProcess(code: Int): Nothing
