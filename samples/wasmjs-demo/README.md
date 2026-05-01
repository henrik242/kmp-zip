# wasmjs-demo

Browser sample for kmp-zip on Kotlin/Wasm. A single HTML page that:

- Decompresses a user-picked `.gz` file with `GzipInputStream` and shows the inflated text.
- Lists entries of a user-picked `.zip` file with `ZipInputStream`. If the archive is encrypted, the page prompts for a password and retries on a wrong guess.

Everything runs in the browser tab — no upload.

## Run

```sh
./gradlew :samples:wasmjs-demo:wasmJsBrowserDevelopmentRun
```

The webpack dev server starts on `http://localhost:8080` and opens the page.

## Build a static distribution

```sh
./gradlew :samples:wasmjs-demo:wasmJsBrowserDistribution
```

Output lands in `build/dist/wasmJs/productionExecutable/`. Serve with any static file server.

## Test against every kmp-zip fixture

```sh
./gradlew :samples:wasmjs-demo:wasmJsNodeTest
```

Runs `SampleFixtureTest` under Node, which feeds every archive in `kmp-zip/src/commonTest/resources/testdata/` (12 unencrypted, 10 password-protected with AES / zip4j / legacy ZipCrypto, 1 gzip) through the sample's `gunzip` / `listZipEntries` and asserts they round-trip correctly. Also exercises the byte-pattern round-trip through the `Uint8Array ↔ ByteArray` shim — the test that originally caught the WHATWG `TextDecoder('latin1')` ↔ `windows-1252` aliasing bug.

## Layout

- `src/wasmJsMain/kotlin/.../Main.kt` — Kotlin's `main()` does the DOM wiring directly via `js("...")` helpers; no `@JsExport` and no JS-side import wrangling. Contains the `gunzip` / `listZipEntries` functions and the `Uint8Array ↔ ByteArray` marshaller (chunked `String.fromCharCode.apply` — a true byte-identity map; do **not** use `TextDecoder('latin1')`, see below).
- `src/wasmJsMain/resources/index.html` — the page. Loads the bundle as a plain `<script src="./wasmjs-demo.js">`. The Kotlin `main()` runs on load and attaches the file-input listeners.
- `src/wasmJsTest/kotlin/.../SampleFixtureTest.kt` — `wasmJsNodeTest` harness; the build script generates `SampleFixtures.kt` from the same `mapping.properties` the kmp-zip module uses.
- `build.gradle.kts` — minimal wasmJs consumer setup; depends on `project(":kmp-zip")`. ~75 lines including the fixture-generator task.

## Marshalling note

`TextDecoder('latin1')` is a WHATWG Encoding alias for windows-1252, which remaps bytes `0x80..0x9F` (e.g. `0x8B` becomes `U+2039`). That breaks the byte-identity round-trip the gzip magic depends on (`0x1F 0x8B`) and surfaces as "Not in gzip format" in real browsers. Kotlin's bundled Node v25.0.0 happens to apply pure Latin-1 for that label, so the bug is invisible under `wasmJsNodeTest` alone — that's why this sample exercises a `0..255` byte pattern and the actual fixture archives. Use `String.fromCharCode.apply` chunks for the bulk JS → wasm marshal instead.
