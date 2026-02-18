# kmp-io

Kotlin Multiplatform library providing `ByteArrayInputStream` and `ZipInputStream` for JVM and iOS targets.

On JVM, the implementations delegate to `java.io` and `java.util.zip`. On iOS/Native, they are pure Kotlin implementations using `platform.zlib` for DEFLATE decompression.

## Targets

- **JVM** (also consumable from Android projects)
- **iosX64**, **iosArm64**, **iosSimulatorArm64**

## API

### `no.synth.kmpio.io`

- `Closeable` — interface extending `AutoCloseable`, works with stdlib `use {}`
- `InputStream` — abstract class with `read()`, `read(ByteArray)`, `read(ByteArray, off, len)`, `readBytes()`, `available()`, `skip(Long)`, `mark(Int)`, `reset()`, `markSupported()`, `close()`
- `ByteArrayInputStream(ByteArray)` / `ByteArrayInputStream(ByteArray, offset, length)` — full Java-compatible API

### `no.synth.kmpio.zip`

- `ZipEntry` — properties: `name`, `size`, `compressedSize`, `crc`, `method`, `isDirectory`, `time`, `comment`, `extra`
- `ZipConstants` — `STORED = 0`, `DEFLATED = 8`
- `ZipInputStream(InputStream)` — `nextEntry`, `closeEntry()`, `read()`, `read(ByteArray, off, len)`, `available()`, `close()`
- `ZipInputStream(ByteArray)` — convenience function

## Installation

Published on [Maven Central](https://central.sonatype.com/artifact/no.synth/kmp-io). No special repository configuration needed.

```kotlin
// build.gradle.kts
dependencies {
    implementation("no.synth:kmp-io:0.6.0")
}
```

For KMP projects:

```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("no.synth:kmp-io:0.6.0")
            }
        }
    }
}
```

## Usage

```kotlin
import no.synth.kmpio.io.ByteArrayInputStream
import no.synth.kmpio.io.readBytes
import no.synth.kmpio.zip.ZipInputStream
import no.synth.kmpio.zip.ZipConstants

// Read from a ByteArray
val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
val b = stream.read() // 1

// Read a ZIP from a ByteArray
ZipInputStream(zipBytes).use { zis ->
    while (true) {
        val entry = zis.nextEntry ?: break
        println("${entry.name} (${if (entry.method == ZipConstants.DEFLATED) "deflated" else "stored"})")
        println(zis.readBytes().decodeToString())
    }
}
```

## Building

Requires JDK 21 and Xcode (for iOS targets).

```sh
./gradlew :kmpio:build                      # Full build
./gradlew :kmpio:jvmTest                    # JVM tests
./gradlew :kmpio:iosSimulatorArm64Test      # iOS simulator tests
```

## Publishing

Tagging a release triggers the GitHub Actions workflow to publish to Maven Central:

```sh
git tag v0.6.0
git push origin v0.6.0
```
