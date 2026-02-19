# kmp-io

Kotlin Multiplatform I/O and ZIP library for JVM and iOS targets.

Provides `ByteArrayInputStream`, `ByteArrayOutputStream`, `ZipInputStream`, and `ZipOutputStream` with a common API across platforms. On JVM, the implementations delegate to `java.io` and `java.util.zip`. On iOS/Native, they are pure Kotlin implementations using `platform.zlib` for DEFLATE compression and decompression.

## Modules

| Artifact | Description |
|----------|-------------|
| `no.synth:kmp-io` | Core I/O and ZIP streams |
| `no.synth:kmp-io-kotlinx` | [kotlinx-io](https://github.com/Kotlin/kotlinx-io) `Source`/`Sink` adapters for the core streams |

## Targets

- **JVM** (also consumable from Android projects)
- **iosX64**, **iosArm64**, **iosSimulatorArm64**

## Installation

Published on [Maven Central](https://central.sonatype.com/artifact/no.synth/kmp-io). No special repository configuration needed.

```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("no.synth:kmp-io:0.6.3")

                // Optional: kotlinx-io adapters
                implementation("no.synth:kmp-io-kotlinx:0.6.3")
            }
        }
    }
}
```

## API

### `kmp-io` — `no.synth.kmpio.io`

| Type | Description |
|------|-------------|
| `InputStream` | Abstract class — `read()`, `read(ByteArray, off, len)`, `available()`, `skip()`, `close()`, etc. |
| `OutputStream` | Abstract class — `write(Int)`, `write(ByteArray, off, len)`, `flush()`, `close()` |
| `ByteArrayInputStream` | Reads from a `ByteArray`. Full Java-compatible API. |
| `ByteArrayOutputStream` | Auto-growing buffer with `toByteArray()`, `size()`, `reset()`, `writeTo()` |
| `InputStream.readBytes()` | Extension that reads all remaining bytes |

### `kmp-io` — `no.synth.kmpio.zip`

| Type | Description |
|------|-------------|
| `ZipInputStream(InputStream)` | Reads ZIP entries — `nextEntry`, `closeEntry()`, `read()`, `readBytes()` |
| `ZipInputStream(ByteArray)` | Convenience factory |
| `ZipOutputStream(OutputStream)` | Writes ZIP entries — `putNextEntry()`, `closeEntry()`, `write()`, `finish()`, `setMethod()`, `setLevel()` |
| `ZipEntry` | Entry metadata — `name`, `size`, `compressedSize`, `crc`, `method`, `isDirectory`, `time`, `comment`, `extra` |
| `ZipConstants` | `STORED = 0`, `DEFLATED = 8` |

### `kmp-io-kotlinx` — `no.synth.kmpio.kotlinx`

| Type | Description |
|------|-------------|
| `SourceInputStream(Source)` | Wraps a kotlinx-io `Source` as an `InputStream` |
| `SinkOutputStream(Sink)` | Wraps a kotlinx-io `Sink` as an `OutputStream` |
| `Source.asInputStream()` | Extension shorthand |
| `Sink.asOutputStream()` | Extension shorthand |
| `ZipInputStream(Source)` | Factory — creates a `ZipInputStream` from a `Source` |
| `ZipOutputStream(Sink)` | Factory — creates a `ZipOutputStream` from a `Sink` |

## Usage

### Read a ZIP from a ByteArray

```kotlin
ZipInputStream(zipBytes).use { zis ->
    while (true) {
        val entry = zis.nextEntry ?: break
        println("${entry.name}: ${zis.readBytes().decodeToString()}")
    }
}
```

### Create a ZIP into a ByteArray

```kotlin
val buf = ByteArrayOutputStream()
ZipOutputStream(buf).use { zos ->
    zos.putNextEntry(ZipEntry("hello.txt"))
    zos.write("Hello, world!".encodeToByteArray())
    zos.closeEntry()
}
val zipBytes = buf.toByteArray()
```

### kotlinx-io: read/write ZIPs via Source/Sink

```kotlin
import kotlinx.io.Buffer
import no.synth.kmpio.kotlinx.ZipInputStream
import no.synth.kmpio.kotlinx.ZipOutputStream

val buffer = Buffer()

// Write
ZipOutputStream(buffer).use { zos ->
    zos.putNextEntry(ZipEntry("hello.txt"))
    zos.write("Hello from kotlinx-io!".encodeToByteArray())
    zos.closeEntry()
}

// Read
ZipInputStream(buffer).use { zis ->
    val entry = zis.nextEntry ?: error("Expected entry")
    println("${entry.name}: ${zis.readBytes().decodeToString()}")
}
```

## Building

Requires JDK 21 and Xcode (for iOS targets).

```sh
./gradlew build                      # Full build
./gradlew jvmTest                    # JVM tests
./gradlew iosSimulatorArm64Test      # iOS simulator tests
```

## Publishing

Tagging a release triggers the GitHub Actions workflow to publish to Maven Central:

```sh
git tag v0.6.3
git push origin v0.6.3
```

## License

[Mozilla Public License 2.0 (MPL-2.0)](https://opensource.org/license/mpl-2-0)
