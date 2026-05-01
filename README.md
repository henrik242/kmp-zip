# kmp-zip

Kotlin Multiplatform ZIP and GZIP library for JVM, iOS, macOS, Linux, Windows, and Kotlin/Wasm (wasmJs) targets, with encryption support.

Provides `ByteArrayInputStream`, `ByteArrayOutputStream`, `ZipInputStream`, `ZipOutputStream`, `GzipInputStream`, and `GzipOutputStream` with a common API across platforms. Supports reading and writing encrypted ZIP archives:
- **WinZip AES** (AES-128/192/256, AE-1 and AE-2 formats) — strong encryption, compatible with 7-Zip, WinRAR, etc.
- **PKWare traditional** (ZipCrypto) — legacy encryption compatible with all ZIP tools including macOS `zip` and Windows Explorer

All ZIP, GZIP, and crypto logic is implemented in common Kotlin. Platform-specific code is limited to thin wrappers around native primitives: `java.util.zip` + `javax.crypto` on JVM, `platform.zlib` + `CommonCrypto` on Apple targets, `platform.zlib` + a pure-Kotlin AES/HMAC/PBKDF2 fallback on Linux and Windows native targets, [pako](https://github.com/nodeca/pako) (MIT) + the same pure-Kotlin crypto on wasmJs.

## Modules

| Artifact | Description |
|----------|-------------|
| `no.synth:kmp-zip` | Core I/O, ZIP, and GZIP streams |
| `no.synth:kmp-zip-kotlinx` | [kotlinx-io](https://github.com/Kotlin/kotlinx-io) `Source`/`Sink` adapters (both directions) for the core streams |
| `no.synth:kmp-zip-okio` | [OkIO](https://square.github.io/okio/) `BufferedSource`/`BufferedSink`/`Source`/`Sink` adapters (both directions) for the core streams |
| `kmp-zip-cli` | Command-line tool for ZIP/GZIP operations — ships as standalone native binaries on macOS / Linux / Windows, with a JVM fallback. Not published to Maven Central. |

## Targets

- **JVM** (also consumable from Android projects)
- **iosArm64**, **iosSimulatorArm64**
- **macosArm64**, **macosX64**
- **linuxX64**, **linuxArm64**
- **mingwX64**
- **wasmJs** (browser, Node 20+) — see [wasmJs notes](#wasmjs-notes) below

## wasmJs notes

The wasmJs target ships the same library API as every other target. There is no `kmp-zip-cli` for wasmJs and no filesystem helpers — work with `ByteArray` and the stream classes, and wire any file I/O on the host side.

A working browser sample lives in [`samples/wasmjs-demo`](samples/wasmjs-demo) — a single page that picks a `.gz` or `.zip` file from disk, runs it through `GzipInputStream` / `ZipInputStream` in the browser tab, and shows the result. Run it with:

```sh
./gradlew :samples:wasmjs-demo:wasmJsBrowserDevelopmentRun
```

That builds the wasm bundle, starts a webpack dev server on `http://localhost:8080`, and opens it. The `samples/wasmjs-demo/build.gradle.kts` is ~20 lines and is the smallest reproducer of a wasmJs consumer setup.

### Runtime caveats:

- **pako runtime dependency.** Deflate/inflate is delegated to [pako 2.1.0](https://github.com/nodeca/pako), pinned exactly. Kotlin's wasmJs build picks it up automatically — the kmp-zip project's `kotlin-js-store/wasm/yarn.lock` records the tarball SHA-512 (`sha512-w+eufiZ1...`). Downstream consumers manage their own lockfile; commit yours and keep `--frozen-lockfile` on in CI. pako adds ~45 KB minified (~14 KB gzipped) to a wasmJs bundle and is not effectively tree-shakeable.
- **`Crypto.randomBytes` requires Web Crypto.** Calls `globalThis.crypto.getRandomValues`, which is available in any browser context (HTTPS *or* plain `http://`) and Node 20+. If the runtime doesn't expose Web Crypto — sandboxed JS realms, browsers with Web Crypto disabled by policy — the call throws `IllegalStateException` naming the likely cause.
- **Long-running compression blocks the UI thread.** pako is synchronous; deflating a multi-MB archive on the main browser thread can stall rendering for hundreds of ms. For anything beyond small archives, run kmp-zip in a `DedicatedWorker`. Kotlin/Wasm has no `window`/`document` dependency, so a worker works without extra setup.

## Threat model

**Don't encrypt long-lived archives in a browser tab.** Use the JVM/Apple targets or do encryption server-side. The pure-Kotlin AES used on Linux, Windows, and wasmJs is table-based and leaks key bits via cache-timing on shared hardware. In a browser, same-origin attacker JS runs in the same renderer process; an in-process leak there recovers the AES key directly — strictly worse than the PBKDF2 brute-force baseline that protects an archive at rest. (PBKDF2 forces an attacker with only the encrypted bytes to brute-force the password; an in-process AES leak skips that step entirely.) JVM and Apple targets use platform AES (`javax.crypto` / `CommonCrypto`) and are not affected.

Decrypting attacker-supplied archives with a user-typed password in the browser is fine — the password is already there. PBKDF2 and HMAC-SHA1 are not table-based and have no known cache-timing leakage in the pure-Kotlin impl.

**Decompression bombs.** kmp-zip does not enforce an upper bound on inflated output; pako has no `maxOutputLength`. A 1 KB compressed stream can inflate to 1 GB. Cap untrusted ZIP reads explicitly:

```kotlin
val MAX_BYTES = 100L * 1024 * 1024  // 100 MB
val out = ByteArray(8192)
var total = 0L
while (true) {
    val n = zis.read(out)
    if (n == -1) break
    total += n
    if (total > MAX_BYTES) error("Entry exceeds size limit")
    sink.write(out, 0, n)
}
```

The `ZipEntry.size` field declares the uncompressed size up front and can be checked before reading; the running counter handles archives that lie about declared size. wasmJs is the most exposed target — browser-side ZIP reading of untrusted input is a liability and should be validated accordingly.

## Installation

Published on [Maven Central](https://central.sonatype.com/artifact/no.synth/kmp-zip). No special repository configuration needed.

```kotlin
kotlin {
    // Add the targets you need, including wasmJs:
    // wasmJs { browser(); nodejs() }

    sourceSets {
        commonMain {
            dependencies {
                implementation("no.synth:kmp-zip:0.11.0")

                // Optional: kotlinx-io adapters
                implementation("no.synth:kmp-zip-kotlinx:0.11.0")

                // Optional: OkIO adapters
                implementation("no.synth:kmp-zip-okio:0.11.0")
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
| `ZipInputStream(InputStream, password?)` | Reads ZIP entries — `nextEntry`, `closeEntry()`, `read()`, `readBytes()`. Pass a password (`ByteArray` or `String`) to decrypt encrypted entries (auto-detects AES or legacy). |
| `ZipInputStream(ByteArray, password?)` | Convenience factory |
| `ZipOutputStream(OutputStream, password?, encryption?, aesStrength?)` | Writes ZIP entries — `putNextEntry()`, `closeEntry()`, `write()`, `finish()`, `setMethod()`, `setLevel()`. Pass a password to encrypt all entries. |
| `ZipEntry` | Entry metadata — `name`, `size`, `compressedSize`, `crc`, `method`, `isDirectory`, `time`, `comment`, `extra` |
| `ZipConstants` | `STORED = 0`, `DEFLATED = 8` |
| `ZipEncryption` | `AES` (default, strong), `LEGACY` (PKWare traditional, for compatibility) |
| `AesStrength` | `AES_128`, `AES_192`, `AES_256` (default) |

### `kmp-zip` — `no.synth.kmpzip.crypto`

| Type | Description |
|------|-------------|
| `Crypto.pbkdf2(password, salt, iterations, keyLengthBytes)` | PBKDF2 key derivation with HMAC-SHA1 |
| `Crypto.hmacSha1(key, data)` | HMAC-SHA1 message authentication |
| `Crypto.crc32(data)` | CRC-32 checksum (pure Kotlin, no platform dependency) |
| `Crypto.randomBytes(size)` | Cryptographically secure random bytes |

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

### Read an AES-encrypted ZIP

```kotlin
ZipInputStream(zipBytes, password = "secret").use { zis ->
    while (true) {
        val entry = zis.nextEntry ?: break
        println("${entry.name}: ${zis.readBytes().decodeToString()}")
    }
}
```

### Create an AES-encrypted ZIP

```kotlin
val buf = ByteArrayOutputStream()
ZipOutputStream(buf, password = "secret").use { zos ->
    zos.putNextEntry(ZipEntry("hello.txt"))
    zos.write("Hello, encrypted world!".encodeToByteArray())
    zos.closeEntry()
}
val encryptedZipBytes = buf.toByteArray()
```

By default, entries are encrypted with AES-256 and DEFLATED compression. You can choose a different strength:

```kotlin
ZipOutputStream(buf, password = "secret", aesStrength = AesStrength.AES_128)
```

### Create a legacy-encrypted ZIP (ZipCrypto)

For maximum compatibility with older tools (macOS Finder, Windows Explorer, `unzip`):

```kotlin
val buf = ByteArrayOutputStream()
ZipOutputStream(buf, password = "secret", encryption = ZipEncryption.LEGACY).use { zos ->
    zos.putNextEntry(ZipEntry("hello.txt"))
    zos.write("Hello, legacy encrypted!".encodeToByteArray())
    zos.closeEntry()
}
```

Reading works the same regardless of encryption method — `ZipInputStream` auto-detects AES vs legacy.

### Standalone crypto primitives

The `Crypto` object provides cross-platform cryptographic primitives that can be used independently of ZIP:

```kotlin
import no.synth.kmpzip.crypto.Crypto

// PBKDF2 key derivation
val salt = Crypto.randomBytes(16)
val key = Crypto.pbkdf2(
    password = "secret".encodeToByteArray(),
    salt = salt,
    iterations = 100_000,
    keyLengthBytes = 32,
)

// HMAC-SHA1
val mac = Crypto.hmacSha1(key, data = "message".encodeToByteArray())

// CRC-32 checksum
val checksum = Crypto.crc32("Hello".encodeToByteArray())

// Secure random
val nonce = Crypto.randomBytes(12)
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

## CLI

The `kmp-zip-cli` module provides a command-line tool for ZIP and GZIP operations, powered by the core library.

### Running

```sh
./kmpzip <command> [options] [args]
```

The `./kmpzip` wrapper picks a native binary for the host (`macosArm64`, `linuxX64`, `linuxArm64`) and execs it directly — no JVM startup. The first invocation lazily builds the binary via Gradle; subsequent runs are instant. Hosts without a native binary fall back to `./gradlew :kmp-zip-cli:jvmRun`. On Windows, use `kmpzip.cmd` instead, which builds and execs the `mingwX64` `.exe`.

**Password encoding:** the `-p` argument is encoded as UTF-8. ASCII passwords interoperate with all common ZIP tools. Non-ASCII passwords work between kmp-zip's own implementations (JVM, native, library API), but may not match `unzip` / Info-ZIP, which use the system locale / CP437.

**Linux binary portability:** the `linuxX64` / `linuxArm64` builds dynamically link the host's glibc. They run on common distros (Debian, Ubuntu, RHEL/Alma/Rocky) and on `gcr.io/distroless/cc-debian12`, but not on Alpine / musl or `gcr.io/distroless/static`. zlib is statically linked, so there's no `libz.so.1` runtime dependency.

### Commands

| Command | Alias | Description |
|---------|-------|-------------|
| `list <file.zip> [-p password]` | `l` | List ZIP contents (method, size, compressed size, name) |
| `extract <file.zip> [-d dir] [-p password]` | `x` | Extract ZIP contents to a directory |
| `create <file.zip> [-p password] [--legacy] <files..>` | `c` | Create ZIP from files and directories (recursive) |
| `gzip <file>` | `z` | GZIP compress a file (creates `<file>.gz`) |
| `gunzip <file.gz>` | `u` | GZIP decompress a file |
| `help` | `-h`, `--help` | Show usage information |

### Examples

```sh
# List contents of a ZIP file
./kmpzip list archive.zip

# Extract to a specific directory
./kmpzip extract archive.zip -d output/

# Create a ZIP from files and directories
./kmpzip create archive.zip file.txt src/

# Create an AES-encrypted ZIP (default)
./kmpzip create secret.zip -p mypassword file.txt

# Create a legacy ZipCrypto-encrypted ZIP (compatible with macOS Finder, Windows Explorer)
./kmpzip create compat.zip -p mypassword --legacy file.txt

# Extract an encrypted ZIP (auto-detects AES or legacy)
./kmpzip extract secret.zip -p mypassword -d output/

# GZIP compress / decompress
./kmpzip gzip largefile.txt
./kmpzip gunzip largefile.txt.gz
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
git tag v0.11.0
git push origin v0.11.0
```

## License

[Mozilla Public License 2.0 (MPL-2.0)](https://opensource.org/license/mpl-2-0)
