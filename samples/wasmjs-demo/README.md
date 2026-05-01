# wasmjs-demo

Browser sample for kmp-zip on Kotlin/Wasm. A single HTML page that:

- Decompresses a user-picked `.gz` file with `GzipInputStream` and shows the inflated text.
- Lists entries of a user-picked `.zip` file with `ZipInputStream`.

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

Output lands in `build/dist/wasmJs/productionExecutable/`. Serve with any static
file server.

## Layout

- `src/wasmJsMain/kotlin/.../Main.kt` — `@JsExport` entry points (`gunzip`, `listZipEntries`) plus a small Latin-1 marshaller for `Uint8Array ↔ ByteArray`.
- `src/wasmJsMain/resources/index.html` — the page itself, with inline JS that wires file inputs to the wasm exports.
- `build.gradle.kts` — minimal wasmJs consumer setup (target + `implementation(project(":kmp-zip"))`).
