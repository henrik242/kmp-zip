package no.synth.kmpzip.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.read
import platform.posix.strerror

// Reads from /dev/urandom via open() with O_CLOEXEC so the fd doesn't leak across
// fork+exec. (Kotlin/Native's platform.posix on linuxX64/linuxArm64 doesn't expose
// getrandom(2), or we'd prefer it for distroless/seccomp portability.)
@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    if (size == 0) return ByteArray(0)
    val fd = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
    if (fd < 0) throw IllegalStateException(errnoMessage("open(/dev/urandom)"))
    try {
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            var produced = 0
            while (produced < size) {
                val got = read(fd, pinned.addressOf(produced), (size - produced).convert())
                if (got < 0L) {
                    if (errno == EINTR) continue
                    throw IllegalStateException(errnoMessage("read(/dev/urandom)"))
                }
                if (got == 0L) {
                    throw IllegalStateException("Unexpected EOF reading /dev/urandom")
                }
                produced += got.toInt()
            }
        }
        return bytes
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun errnoMessage(op: String): String =
    "$op failed: errno=$errno (${strerror(errno)?.toKString() ?: "unknown"})"
