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
// kmp-zip module uses for its own wasmJsTest — we reuse the same mapping.
val sharedTestdataDir = rootProject.layout.projectDirectory.dir("kmp-zip/src/commonTest/resources/testdata")
val sharedTestdataMapping = sharedTestdataDir.file("mapping.properties")

val generateSampleTestFixtures by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sampleFixtures/kotlin")
    inputs.dir(sharedTestdataDir)
    outputs.dir(outputDir)
    doLast {
        val outDir = outputDir.get().asFile.resolve("no/synth/kmpzip/sample")
        outDir.mkdirs()
        val mapping = sharedTestdataMapping.asFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val (prop, file) = line.split('=', limit = 2)
                prop.trim() to file.trim()
            }
        val sb = StringBuilder()
        sb.append("package no.synth.kmpzip.sample\n\n")
        sb.append("import kotlin.io.encoding.Base64\n")
        sb.append("import kotlin.io.encoding.ExperimentalEncodingApi\n\n")
        sb.append("@OptIn(ExperimentalEncodingApi::class)\n")
        sb.append("internal object SampleFixtures {\n")
        for ((prop, fileName) in mapping) {
            val bytes = sharedTestdataDir.file(fileName).asFile.readBytes()
            val b64 = Base64.getEncoder().encodeToString(bytes)
            sb.append("    val $prop: ByteArray get() = Base64.decode(\"$b64\")\n")
        }
        sb.append("}\n")
        outDir.resolve("SampleFixtures.kt").writeText(sb.toString())
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "wasmjs-demo.js"
            }
        }
        // Tests run under Node — the same code as the published bundle, just
        // exercised via wasmJsNodeTest instead of needing a real browser.
        nodejs()
        binaries.executable()
    }

    sourceSets {
        getByName("wasmJsMain") {
            dependencies {
                implementation(project(":kmp-zip"))
            }
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        getByName("wasmJsTest") {
            dependencies {
                implementation(kotlin("test"))
            }
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            kotlin.srcDir(generateSampleTestFixtures.map {
                layout.buildDirectory.dir("generated/sampleFixtures/kotlin")
            })
        }
    }
}
