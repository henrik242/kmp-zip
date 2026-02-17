# kmp-libs

Kotlin Multiplatform library providing `ByteArrayInputStream` and `ZipInputStream` for JVM and iOS targets.

On JVM, the implementations delegate to `java.io` and `java.util.zip`. On iOS/Native, they are pure Kotlin implementations using `platform.zlib` for DEFLATE decompression.

## Targets

- **JVM** (also consumable from Android projects)
- **iosX64**, **iosArm64**, **iosSimulatorArm64**

## API

### `no.synth.kmplibs.io`

- `Closeable` — interface extending `AutoCloseable`, works with stdlib `use {}`
- `InputStream` — abstract class with `read()`, `read(ByteArray)`, `read(ByteArray, off, len)`, `readBytes()`, `available()`, `skip(Long)`, `mark(Int)`, `reset()`, `markSupported()`, `close()`
- `ByteArrayInputStream(ByteArray)` / `ByteArrayInputStream(ByteArray, offset, length)` — full Java-compatible API

### `no.synth.kmplibs.zip`

- `ZipEntry` — properties: `name`, `size`, `compressedSize`, `crc`, `method`, `isDirectory`, `time`, `comment`, `extra`
- `ZipConstants` — `STORED = 0`, `DEFLATED = 8`
- `ZipInputStream(InputStream)` — `nextEntry`, `closeEntry()`, `read()`, `read(ByteArray, off, len)`, `available()`, `close()`
- `ZipInputStream(ByteArray)` — convenience function

## Installation

Published artifacts are available at https://henrik242.github.io/kmp-libs.

Add the repository and dependency to your Gradle build:

```kotlin
// settings.gradle.kts (or root build.gradle.kts)
repositories {
    maven("https://henrik242.github.io/kmp-libs")
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("no.synth.kmplibs:library:0.3.0")
}
```

For KMP projects:

```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("no.synth.kmplibs:library:0.3.0")
            }
        }
    }
}
```

### Maven Local

```sh
# In this repo:
./gradlew :library:publishToMavenLocal

# In your consuming project, add mavenLocal():
# repositories { mavenLocal() }
```

## Usage

```kotlin
import no.synth.kmplibs.io.ByteArrayInputStream
import no.synth.kmplibs.zip.ZipInputStream
import no.synth.kmplibs.zip.ZipConstants

// Read from a ByteArray
val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
val b = stream.read() // 1

// Read a ZIP from a ByteArray
val zis = ZipInputStream(zipBytes)
while (true) {
    val entry = zis.getNextEntry() ?: break
    println("${entry.name} (${if (entry.method == ZipConstants.DEFLATED) "deflated" else "stored"})")

    val buf = ByteArray(4096)
    val content = buildList {
        while (true) {
            val n = zis.read(buf, 0, buf.size)
            if (n == -1) break
            addAll(buf.take(n))
        }
    }.toByteArray()

    println(content.decodeToString())
}
zis.close()
```

## Building

Requires JDK 21 and Xcode (for iOS targets).

```sh
./gradlew :library:build                      # Full build
./gradlew :library:jvmTest                    # JVM tests
./gradlew :library:iosSimulatorArm64Test      # iOS simulator tests
```

## Publishing

Tagging a release triggers the GitHub Actions publish workflow:

```sh
git tag v0.1.0
git push origin v0.1.0
```
