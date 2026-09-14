package no.synth.kmpzip.io

actual open class IOException : Exception {
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}
