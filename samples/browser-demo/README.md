# browser-demo

Browser sample for kmp-zip on Kotlin/JS and Kotlin/Wasm — one source set, built
for both targets. A single HTML page that:

- Decompresses a user-picked `.gz` file with `GzipInputStream` and shows the inflated text.
- Lists entries of a user-picked `.zip` file with `ZipInputStream`. If the archive is encrypted, the page prompts for a password and retries on a wrong guess.

Everything runs in the browser tab — no upload.

## Run

```sh
./gradlew :samples:browser-demo:jsBrowserDevelopmentRun
./gradlew :samples:browser-demo:wasmJsBrowserDevelopmentRun
```

Either one starts a webpack dev server on `http://localhost:8080` and opens the
page; the two produce the same UI from the same Kotlin.

## Build a static distribution

```sh
./gradlew :samples:browser-demo:jsBrowserDistribution
./gradlew :samples:browser-demo:wasmJsBrowserDistribution
```

Output lands in `build/dist/js/productionExecutable/` and
`build/dist/wasmJs/productionExecutable/`, each self-contained with its own
`demo.js`. Serve with any static file server.

## Test against every kmp-zip fixture

```sh
./gradlew :samples:browser-demo:jsNodeTest
./gradlew :samples:browser-demo:wasmJsNodeTest
```

Runs `SampleFixtureTest` under Node on either target, which feeds every archive in `kmp-zip/src/commonTest/resources/testdata/` (12 unencrypted, 10 password-protected with AES / zip4j / legacy ZipCrypto, 1 gzip) through the sample's `gunzip` / `listZipEntries` and asserts they round-trip correctly. Also exercises the byte-pattern round-trip through the `Uint8Array ↔ ByteArray` shim — the test that originally caught the WHATWG `TextDecoder('latin1')` ↔ `windows-1252` aliasing bug.

## Layout

- `src/webMain/kotlin/.../Main.kt` — Kotlin's `main()` does the DOM wiring directly via `js("...")` helpers; no `@JsExport` and no JS-side import wrangling. Contains the `gunzip` / `listZipEntries` functions and the `Uint8Array ↔ ByteArray` marshaller (chunked `String.fromCharCode.apply` — a true byte-identity map; do **not** use `TextDecoder('latin1')`, see below). `webMain` is KGP's shared js+wasmJs source set, so this one file builds for both; the file-picker glue uses `.then()` rather than `async`/`await` because Kotlin/JS parses the `js()` body and rejects async functions.
- `src/webMain/resources/index.html` — the page. Loads the bundle as a plain `<script src="./demo.js">`, the output name both targets use. The Kotlin `main()` runs on load and attaches the file-input listeners.
- `src/webTest/kotlin/.../SampleFixtureTest.kt` — the node-test harness for both targets; the build script generates `SampleFixtures.kt` from the same `mapping.properties` the kmp-zip module uses.
- `build.gradle.kts` — minimal consumer setup; depends on `project(":kmp-zip")`. The js target deliberately keeps the default UMD module kind, so `jsBrowserProductionWebpack` doubles as a check that a plain browser consumer links kmp-zip without dragging in the Node-only `node:fs` binding behind `fileSeekableSource`.

## Marshalling note

`TextDecoder('latin1')` is a WHATWG Encoding alias for windows-1252, which remaps bytes `0x80..0x9F` (e.g. `0x8B` becomes `U+2039`). That breaks the byte-identity round-trip the gzip magic depends on (`0x1F 0x8B`) and surfaces as "Not in gzip format" in real browsers. Kotlin's bundled Node v25.0.0 happens to apply pure Latin-1 for that label, so the bug is invisible under the node tests alone — that's why this sample exercises a `0..255` byte pattern and the actual fixture archives. Use `String.fromCharCode.apply` chunks for the bulk JS → wasm marshal instead.
