# kmp-zip

Kotlin Multiplatform ZIP and GZIP library for JVM and iOS targets.

Provides `ByteArrayInputStream`, `ByteArrayOutputStream`, `ZipInputStream`, `ZipOutputStream`, `GzipInputStream`, and `GzipOutputStream` with a common API across platforms. On JVM, the implementations delegate to `java.io` and `java.util.zip`. On iOS/Native, they are pure Kotlin implementations using `platform.zlib` for DEFLATE compression and decompression.

## Modules

| Artifact | Description |
|----------|-------------|
| `no.synth:kmp-zip` | Core I/O, ZIP, and GZIP streams |
| `no.synth:kmp-zip-kotlinx` | [kotlinx-io](https://github.com/Kotlin/kotlinx-io) `Source`/`Sink` adapters (both directions) for the core streams |
| `no.synth:kmp-zip-okio` | [OkIO](https://square.github.io/okio/) `BufferedSource`/`BufferedSink`/`Source`/`Sink` adapters (both directions) for the core streams |

## Targets

- **JVM** (also consumable from Android projects)
- **iosX64**, **iosArm64**, **iosSimulatorArm64**

## Installation

Published on [Maven Central](https://central.sonatype.com/artifact/no.synth/kmp-zip). No special repository configuration needed.

```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("no.synth:kmp-zip:0.8.0")

                // Optional: kotlinx-io adapters
                implementation("no.synth:kmp-zip-kotlinx:0.8.0")

                // Optional: OkIO adapters
                implementation("no.synth:kmp-zip-okio:0.8.0")
            }
        }
    }
}
```

## API

### `kmp-zip` — `no.synth.kmpzip.io`

| Type | Description |
|------|-------------|
| `InputStream` | Abstract class — `read()`, `read(ByteArray, off, len)`, `available()`, `skip()`, `close()`, etc. |
| `OutputStream` | Abstract class — `write(Int)`, `write(ByteArray, off, len)`, `flush()`, `close()` |
| `ByteArrayInputStream` | Reads from a `ByteArray`. Full Java-compatible API. |
| `ByteArrayOutputStream` | Auto-growing buffer with `toByteArray()`, `size()`, `reset()`, `writeTo()` |
| `InputStream.readBytes()` | Extension that reads all remaining bytes |

### `kmp-zip` — `no.synth.kmpzip.zip`

| Type | Description |
|------|-------------|
| `ZipInputStream(InputStream)` | Reads ZIP entries — `nextEntry`, `closeEntry()`, `read()`, `readBytes()` |
| `ZipInputStream(ByteArray)` | Convenience factory |
| `ZipOutputStream(OutputStream)` | Writes ZIP entries — `putNextEntry()`, `closeEntry()`, `write()`, `finish()`, `setMethod()`, `setLevel()` |
| `ZipEntry` | Entry metadata — `name`, `size`, `compressedSize`, `crc`, `method`, `isDirectory`, `time`, `comment`, `extra` |
| `ZipConstants` | `STORED = 0`, `DEFLATED = 8` |

### `kmp-zip` — `no.synth.kmpzip.gzip`

| Type | Description |
|------|-------------|
| `GzipInputStream(InputStream)` | Decompresses a GZIP stream — `read()`, `available()`, `close()` |
| `GzipInputStream(ByteArray)` | Convenience factory |
| `GzipOutputStream(OutputStream)` | Compresses data in GZIP format — `write()`, `finish()`, `flush()`, `close()` |

### `kmp-zip-kotlinx` — `no.synth.kmpzip.kotlinx`

| Type | Description |
|------|-------------|
| `SourceInputStream(Source)` | Wraps a kotlinx-io `Source` as an `InputStream` |
| `SinkOutputStream(Sink)` | Wraps a kotlinx-io `Sink` as an `OutputStream` |
| `InputStreamSource(InputStream)` | Wraps an `InputStream` as a kotlinx-io `RawSource` |
| `OutputStreamSink(OutputStream)` | Wraps an `OutputStream` as a kotlinx-io `RawSink` |
| `Source.asInputStream()` | Extension shorthand |
| `Sink.asOutputStream()` | Extension shorthand |
| `InputStream.asSource()` | Extension shorthand |
| `OutputStream.asSink()` | Extension shorthand |
| `ZipInputStream(Source)` | Factory — creates a `ZipInputStream` from a `Source` |
| `ZipOutputStream(Sink)` | Factory — creates a `ZipOutputStream` from a `Sink` |
| `GzipInputStream(Source)` | Factory — creates a `GzipInputStream` from a `Source` |
| `GzipOutputStream(Sink)` | Factory — creates a `GzipOutputStream` from a `Sink` |

### `kmp-zip-okio` — `no.synth.kmpzip.okio`

| Type | Description |
|------|-------------|
| `SourceInputStream(BufferedSource)` | Wraps an OkIO `BufferedSource` as an `InputStream` |
| `SinkOutputStream(BufferedSink)` | Wraps an OkIO `BufferedSink` as an `OutputStream` |
| `InputStreamSource(InputStream)` | Wraps an `InputStream` as an OkIO `Source` |
| `OutputStreamSink(OutputStream)` | Wraps an `OutputStream` as an OkIO `Sink` |
| `BufferedSource.asInputStream()` | Extension shorthand |
| `BufferedSink.asOutputStream()` | Extension shorthand |
| `InputStream.asSource()` | Extension shorthand |
| `OutputStream.asSink()` | Extension shorthand |
| `ZipInputStream(BufferedSource)` | Factory — creates a `ZipInputStream` from a `BufferedSource` |
| `ZipOutputStream(BufferedSink)` | Factory — creates a `ZipOutputStream` from a `BufferedSink` |
| `GzipInputStream(BufferedSource)` | Factory — creates a `GzipInputStream` from a `BufferedSource` |
| `GzipOutputStream(BufferedSink)` | Factory — creates a `GzipOutputStream` from a `BufferedSink` |

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

### GZIP compress and decompress

```kotlin
// Compress
val buf = ByteArrayOutputStream()
GzipOutputStream(buf).use { gzos ->
    gzos.write("Hello, world!".encodeToByteArray())
}
val gzipped = buf.toByteArray()

// Decompress
val text = GzipInputStream(gzipped).use { gzis ->
    gzis.readBytes().decodeToString()
}
```

### kotlinx-io: read/write ZIPs via Source/Sink

```kotlin
import kotlinx.io.Buffer
import no.synth.kmpzip.kotlinx.ZipInputStream
import no.synth.kmpzip.kotlinx.ZipOutputStream

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

### OkIO: read/write ZIPs via BufferedSource/BufferedSink

```kotlin
import okio.Buffer
import no.synth.kmpzip.okio.ZipInputStream
import no.synth.kmpzip.okio.ZipOutputStream

val buffer = Buffer()

// Write
ZipOutputStream(buffer).use { zos ->
    zos.putNextEntry(ZipEntry("hello.txt"))
    zos.write("Hello from OkIO!".encodeToByteArray())
    zos.closeEntry()
}

// Read
ZipInputStream(buffer).use { zis ->
    val entry = zis.nextEntry ?: error("Expected entry")
    println("${entry.name}: ${zis.readBytes().decodeToString()}")
}
```

### Stream data from a ZIP entry via Source

The `InputStream.asSource()` adapter lets you read a ZIP entry through a `Source`, which is useful
for streaming deserialization (e.g. kotlinx-serialization's `decodeFromBufferedSource`).

**OkIO:**

```kotlin
import okio.buffer
import no.synth.kmpzip.okio.ZipInputStream
import no.synth.kmpzip.okio.asSource

ZipInputStream(source).use { zis ->
    val entry = zis.nextEntry
    if (entry != null) {
        val entrySource = zis.asSource().buffer()
        // Stream directly from the ZIP entry — e.g. Json.decodeFromBufferedSource(serializer, entrySource)
        println("${entry.name}: ${entrySource.readUtf8()}")
    }
}
```

**kotlinx-io:**

```kotlin
import kotlinx.io.buffered
import kotlinx.io.readString
import no.synth.kmpzip.kotlinx.ZipInputStream
import no.synth.kmpzip.kotlinx.asSource

ZipInputStream(source).use { zis ->
    val entry = zis.nextEntry
    if (entry != null) {
        val entrySource = zis.asSource().buffered()
        // Stream directly from the ZIP entry — e.g. Json.decodeFromBufferedSource(serializer, entrySource)
        println("${entry.name}: ${entrySource.readString()}")
    }
}
```

### Stream data directly into a ZIP entry via Sink

The `OutputStream.asSink()` adapter lets you write into
a ZIP entry through a `Sink`, which is useful for streaming serialization (e.g. kotlinx-serialization's
`encodeToSink`).

**OkIO:**

```kotlin
import okio.buffer
import no.synth.kmpzip.okio.ZipOutputStream
import no.synth.kmpzip.okio.asSink

ZipOutputStream(sink).use { zos ->
    zos.putNextEntry(ZipEntry("data.json"))
    val entrySink = zos.asSink().buffer()
    // Stream directly into the ZIP entry — e.g. Json.encodeToSink(serializer, value, entrySink)
    entrySink.writeUtf8("""{"hello": "world"}""")
    entrySink.flush()
    zos.closeEntry()
}
```

**kotlinx-io:**

```kotlin
import kotlinx.io.buffered
import kotlinx.io.writeString
import no.synth.kmpzip.kotlinx.ZipOutputStream
import no.synth.kmpzip.kotlinx.asSink

ZipOutputStream(sink).use { zos ->
    zos.putNextEntry(ZipEntry("data.json"))
    val entrySink = zos.asSink().buffered()
    // Stream directly into the ZIP entry — e.g. Json.encodeToSink(serializer, value, entrySink)
    entrySink.writeString("""{"hello": "world"}""")
    entrySink.flush()
    zos.closeEntry()
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
git tag v0.8.0
git push origin v0.8.0
```

## License

[Mozilla Public License 2.0 (MPL-2.0)](https://opensource.org/license/mpl-2-0)
