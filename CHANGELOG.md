# Changelog

All notable changes to this project are documented here. Entries were
reconstructed from the git tag history. Dates are the tag dates. This project
follows semantic versioning; the pre-1.0 series broke API where noted.

## [Unreleased]

### Added
- Typed read exceptions, all extending a new multiplatform `no.synth.kmpzip.io.IOException`
  (a typealias to `java.io.IOException` on the JVM): `ZipException` base with
  `ZipFormatException` and `ZipUnsupportedFeatureException`; `GzipException` base with
  `GzipFormatException`. All exception constructors take an optional `cause`.

### Changed
- Read errors are now `IOException` subtypes, not bare `Exception`, so `catch (IOException)`
  on the JVM still catches them. JVM GZIP errors are now `GzipFormatException` (was
  `java.util.zip.ZipException`/`EOFException`), matching every other target.
- `ZipPasswordException` and `NoProgressException` now extend the new `IOException`
  (were `Exception`); `NoProgressException` gained an optional `cause`.
- `ZipPasswordException` message on legacy (non-AES) entries now reads "Wrong password
  or corrupt data", since ZipCrypto has no MAC to tell them apart. A CRC mismatch on a
  WinZip AES entry now reports `ZipFormatException`, not `ZipPasswordException` (the auth
  code already proved the key).
- `Crypto.CRC32_TABLE` is now internal.
- No public path throws a bare `Exception` any more. Write-path misuse throws
  `IllegalStateException` (closed stream, no current entry) or `IllegalArgumentException`
  (invalid method, missing STORED size/crc); the non-JVM `reset()` throws
  `UnsupportedOperationException` (on the JVM, `InputStream` is `java.io.InputStream`,
  whose `reset()` throws `IOException`); native file-source I/O failures throw `IOException`.

## [0.16.0] - 2026-09-08

### Added
- `NoProgressException`, thrown when the deflater or inflater makes no forward
  progress, so a corrupt or truncated stream fails fast instead of spinning.

### Changed
- `read()` returns 0 for a zero-length request instead of failing; the gzip
  reader and `readBytes()` share the no-progress guard.
- Kotlin 2.4.20, pako 3.0.1, okio updated. Test tasks get a task timeout so a
  hung JS/wasmJs runner is reaped instead of burning cores.

## [0.15.0] - 2026-08-26

### Added
- Kotlin/JS (`js`) target alongside `wasmJs`. The browser sample builds for both.

### Changed
- Adapter default-dispatcher actual shared across non-JVM targets. Test-data
  generators resolve at configuration time (configuration-cache friendly).

## [0.14.0] - 2026-08-22

### Added
- Java 8 support: published JVM artifacts emit class version 52 and link only
  against Java 8 APIs. CI checks the baseline, runs the JVM suite on JDK 8, and
  resolves the artifact from a Java 8 consumer build.
- `@JvmOverloads` on the public constructors and factories.

### Fixed
- Entries with no time set now get a valid DOS timestamp instead of a value that
  reads back as the year 2108.
- Build fails clearly when a stale jar sits in `build/libs`.

### Changed
- Gradle 9.7.1. `ZipEntry` simplified.

## [0.13.0] - 2026-07-24

### Added
- `isGzip` and `isZip` content sniffers.

## [0.12.2] - 2026-07-08

### Changed
- Kotlin 2.4.0. Gradle caching/parallel config, `dependencyUpdates` config,
  perf-harness hardening (warmup, throughput, RSS, pinned runners, baseline check).
- Dropped the `kmpzip` / `kmpzip.cmd` wrappers; install and build paths documented instead.

## [0.12.1] - 2026-06-03

### Fixed
- Bulk AES-CTR keystream generation, fixing slow decryption of large entries.

## [0.12.0] - 2026-06-01

### Added
- `ZipFile`: random-access reader over a `SeekableSource` that parses the central
  directory and seeks straight to one entry without streaming the whole archive.

## [0.11.3] - 2026-05-26

### Added
- `wasmJs` target for the okio and kotlinx adapter modules.

### Changed
- `FileSystemHelpers` and the CLI simplified.

## [0.11.2] - 2026-05-05

### Added
- `FileSystem.zipTo` / `FileSystem.unzipFrom` suspend helpers (okio and kotlinx).
- Perf benchmark script.

### Changed
- CLI migrated onto the new helpers; `create`/`extract` renamed to `zip`/`unzip`.

## [0.11.1] - 2026-05-03

### Fixed
- Native gunzip crash on inputs larger than the 8 KB read buffer.
- Hardened the non-JVM `GzipInputStream` and the inflate/deflate paths.

## [0.11.0] - 2026-05-01

### Added
- `wasmJs` target and a browser sample.

### Fixed
- `Uint8Array` marshalling, verified against every testdata fixture.

## [0.10.1] - 2026-04-30

### Added
- Re-added the macOS Intel (`macosX64`) target.

## [0.10.0] - 2026-04-29

### Added
- Native targets: macOS, Linux, and Windows.
- CLI module for ZIP/GZIP operations.

### Changed
- Non-ZIP and non-gzip input is rejected with a clear error.
- Entry CRC is verified on read, so a wrong password is detected reliably.
- `ZipInputStream` no longer swallows password and corruption exceptions.

## [0.9.2] - 2026-03-25

### Fixed
- Legacy ZipCrypto data-descriptor handling; added Zip4j interop tests.

## [0.9.1] - 2026-03-25

### Fixed
- AES decryption of Zip4j-created files that use data descriptors, via a bounded
  data-descriptor scan.

## [0.9.0] - 2026-03-21

### Added
- WinZip AES encryption (AES-128/192/256).
- PKWare traditional (legacy ZipCrypto) encryption.

### Changed
- Kotlin 2.3.20.

## [0.8.0] - 2026-03-12

### Added
- Reverse stream adapters: `OutputStream` to `Sink`, `InputStream` to `Source`.

### Changed
- Gradle 9.4. GZIP streams documented in the README.

## [0.7.2] - 2026-03-05

### Added
- `GzipInputStream` and `GzipOutputStream`.
- CodeQL workflow.

### Changed
- Group and version centralised across all modules.

## [0.7.1] - 2026-02-24

### Added
- `kmp-zip-okio` module: OkIO `BufferedSource`/`BufferedSink` adapters.

## [0.7.0] - 2026-02-19

### Changed
- Renamed `kmp-io` to `kmp-zip` with a full package rename. Coordinates are now
  `no.synth:kmp-zip`. (Breaking.)
- Removed non-null assertions.

## [0.6.3] - 2026-02-19

### Added
- `kmp-io-kotlinx` module: kotlinx-io `Source`/`Sink` ZIP adapters.

## [0.6.2] - 2026-02-18

### Added
- `ByteArrayOutputStream` and `ZipOutputStream` (JVM and iOS).

### Changed
- Project structure flattened (library submodule removed).

## [0.6.1-relocation] - 2026-02-18

### Added
- Relocation POM for the old `no.synth.kmplibs:library` coordinates.

## [0.6.0] - 2026-02-18

### Changed
- Renamed `kmp-libs` to `kmp-io`; coordinate is now `no.synth:kmp-io`. (Breaking.)
- License changed to MPL 2.0.

## [0.5.0] - 2026-02-17

### Changed
- Corrected the license metadata. Publications signed only in CI.

## [0.4.0] - 2026-02-17

### Added
- `readBytes()` member on `ZipInputStream`; invalid ZIP data handled.

### Changed
- Publishing switched to Maven Central.

## [0.3.0] - 2026-02-17

### Changed
- `nextEntry` is now a member property.

## [0.2.0] - 2026-02-17

### Added
- `nextEntry` and `readBytes()`; Java API compatibility.

## [0.1.0] - 2026-02-17

### Added
- Initial release: Kotlin Multiplatform `ByteArrayInputStream` and `ZipInputStream`.
