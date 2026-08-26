import java.util.Base64
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

// Embed every fixture under kmp-zip's testdata as a base64-decoded ByteArray
// property on a generated `SampleFixtures` object. Mirrors the approach the
// kmp-zip module uses for its own webTest — we reuse the same mapping.
val sharedTestdataDir = rootProject.layout.projectDirectory.dir("kmp-zip/src/commonTest/resources/testdata")
val sharedTestdataMapping = sharedTestdataDir.file("mapping.properties")

val generateSampleTestFixtures = tasks.register("generateSampleTestFixtures") {
    val outputDir = layout.buildDirectory.dir("generated/sampleFixtures/kotlin")
    // Read at configuration time; see the same note in kmp-zip/build.gradle.kts.
    val testdataDir = sharedTestdataDir
    val fixtures = sharedTestdataMapping.asFile.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val (prop, file) = line.split('=', limit = 2)
            prop.trim() to file.trim()
        }
    inputs.dir(testdataDir)
    outputs.dir(outputDir)
    doLast {
        val outDir = outputDir.get().asFile.resolve("no/synth/kmpzip/sample")
        outDir.mkdirs()
        val sb = StringBuilder()
        sb.append("package no.synth.kmpzip.sample\n\n")
        sb.append("import kotlin.io.encoding.Base64\n")
        sb.append("import kotlin.io.encoding.ExperimentalEncodingApi\n\n")
        sb.append("@OptIn(ExperimentalEncodingApi::class)\n")
        sb.append("internal object SampleFixtures {\n")
        for ((prop, fileName) in fixtures) {
            val bytes = testdataDir.file(fileName).asFile.readBytes()
            val b64 = Base64.getEncoder().encodeToString(bytes)
            sb.append("    val $prop: ByteArray get() = Base64.decode(\"$b64\")\n")
        }
        sb.append("}\n")
        outDir.resolve("SampleFixtures.kt").writeText(sb.toString())
    }
}

kotlin {
    // Both JS-hosted targets build the same sample from one source set, so the
    // page exercises the library on js and on wasmJs. The js target keeps the
    // default UMD module kind on purpose: that is what an ordinary consumer
    // gets, and it proves a browser bundle links kmp-zip without pulling in the
    // Node-only `node:fs` binding behind fileSeekableSource.
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "demo.js"
            }
        }
        nodejs()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "demo.js"
            }
        }
        // Tests run under Node — the same code as the published bundle, just
        // exercised via the node test tasks instead of needing a real browser.
        nodejs()
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("webMain") {
            dependencies {
                implementation(project(":kmp-zip"))
            }
        }
        getByName("webTest") {
            dependencies {
                implementation(kotlin("test"))
            }
            kotlin.srcDir(generateSampleTestFixtures.map {
                layout.buildDirectory.dir("generated/sampleFixtures/kotlin")
            })
        }
        listOf("webMain", "webTest", "jsMain", "jsTest", "wasmJsMain", "wasmJsTest").forEach {
            sourceSets[it].languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
}
