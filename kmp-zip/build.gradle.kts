import java.util.Base64
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
}

// Generate platform-specific TestData files from templates. The TestData property
// → fixture-filename mapping lives in commonTest/resources/testdata/mapping.properties
// so all three generators (apple/nativeNonApple/wasmJs) read the same source.
//
// resourceDirPath: backslashes on Windows are normalized to forward slashes —
// both because Kotlin string literals would interpret `\U`/`\t` as escape codes
// and because fopen accepts mixed-slash paths.
val resourceDir = layout.projectDirectory.dir("src/commonTest/resources/testdata")
val resourceDirPath = resourceDir.asFile.absolutePath.replace('\\', '/')
val testDataMappingFile = resourceDir.file("mapping.properties")

fun readTestDataMapping(): List<Pair<String, String>> =
    testDataMappingFile.asFile.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val (prop, file) = line.split('=', limit = 2)
            prop.trim() to file.trim()
        }

fun renderActualProperties(): String =
    readTestDataMapping().joinToString("\n") { (prop, file) ->
        "    actual val $prop: ByteArray get() = loadResource(\"$file\")"
    }

// Substitute @@RESOURCE_DIR@@ and @@PROPERTIES@@ in a TestData template, write
// the result to a generated source dir. Used for the apple and nativeNonApple
// targets, which load fixtures from the filesystem at test time.
fun registerTemplatedTestData(
    name: String,
    templatePath: String,
    generatedDirName: String,
) = tasks.register(name) {
    val templateFile = layout.projectDirectory.file(templatePath)
    val outputDir = layout.buildDirectory.dir("generated/$generatedDirName/kotlin")
    val outputFileName = templateFile.asFile.name.removeSuffix(".template")
    inputs.file(templateFile)
    inputs.file(testDataMappingFile)
    inputs.property("resourceDir", resourceDirPath)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("no/synth/kmpzip/zip")
        dir.mkdirs()
        val content = templateFile.asFile.readText()
            .replace("@@RESOURCE_DIR@@", resourceDirPath)
            .replace("@@PROPERTIES@@", renderActualProperties())
        dir.resolve(outputFileName).writeText(content)
    }
}

val generateAppleTestData = registerTemplatedTestData(
    name = "generateAppleTestData",
    templatePath = "src/appleTest/kotlin/no/synth/kmpzip/zip/TestData.apple.kt.template",
    generatedDirName = "appleTestData",
)

val generateNativeNonAppleTestData = registerTemplatedTestData(
    name = "generateNativeNonAppleTestData",
    templatePath = "src/nativeNonAppleTest/kotlin/no/synth/kmpzip/zip/TestData.nativeNonApple.kt.template",
    generatedDirName = "nativeNonAppleTestData",
)

// wasmJs has no filesystem at test time, so the apple/nativeNonApple "fopen the
// path" trick doesn't work. Embed every fixture as base64 in a generated Kotlin
// source file; the test code decodes on demand. ~92 KB of fixtures → ~125 KB of
// source, only in the test klib.
val generateWasmJsTestData by tasks.registering {
    val resDir = resourceDir
    val outputDir = layout.buildDirectory.dir("generated/wasmJsTestData/kotlin")
    inputs.dir(resDir)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("no/synth/kmpzip/zip")
        dir.mkdirs()
        val files = resDir.asFile.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".zip") || it.name.endsWith(".gz")) }
            ?.sortedBy { it.name }
            ?: error("Test resource dir is empty: ${resDir.asFile}")
        val sb = StringBuilder()
        sb.append("package no.synth.kmpzip.zip\n\n")
        sb.append("import kotlin.io.encoding.Base64\n")
        sb.append("import kotlin.io.encoding.ExperimentalEncodingApi\n\n")
        sb.append("@OptIn(ExperimentalEncodingApi::class)\n")
        sb.append("actual object TestData {\n")
        sb.append("    private val RESOURCES: Map<String, String> = mapOf(\n")
        for (f in files) {
            val b64 = Base64.getEncoder().encodeToString(f.readBytes())
            sb.append("        \"${f.name}\" to \"$b64\",\n")
        }
        sb.append("    )\n\n")
        sb.append("    actual fun loadResource(name: String): ByteArray =\n")
        sb.append("        Base64.decode(RESOURCES[name] ?: error(\"Test resource not found: \$name\"))\n\n")
        sb.append(renderActualProperties())
        sb.append("\n}\n")
        dir.resolve("TestData.wasmJs.kt").writeText(sb.toString())
    }
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64 {
        // bcrypt isn't auto-linked by Kotlin/Native's platform.windows cinterop;
        // BCryptGenRandom in SecureRandom.mingw.kt needs an explicit -lbcrypt.
        binaries.all { linkerOpts("-lbcrypt") }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // Both environments are registered so consumers see explicit browser/Node
        // support, but the browser test task is disabled — running it would try
        // to download a headless Chromium onto CI runners.
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Pure-Kotlin actuals (IO streams, gzip wrappers) shared by every
        // non-JVM target. Named to match kotlin-stdlib's own `commonNonJvmMain`
        // fragment so KGP merges them and skips the duplicate-klib warning.
        val commonNonJvmMain by creating { dependsOn(sourceSets["commonMain"]) }
        sourceSets["nativeMain"].dependsOn(commonNonJvmMain)
        sourceSets["wasmJsMain"].apply {
            dependsOn(commonNonJvmMain)
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        sourceSets["wasmJsTest"].apply {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }

        // Holds the AES / HMAC / PBKDF2 wrappers that delegate to the pure-Kotlin
        // crypto in commonMain. Shared by every target without a platform crypto
        // library (linux/mingw native + wasmJs). Apple/JVM use platform impls.
        val pureKotlinCryptoMain by creating { dependsOn(sourceSets["commonMain"]) }
        sourceSets["wasmJsMain"].dependsOn(pureKotlinCryptoMain)

        val nativeNonAppleMain by creating {
            dependsOn(sourceSets["nativeMain"])
            dependsOn(pureKotlinCryptoMain)
        }
        val nativeNonAppleTest by creating { dependsOn(sourceSets["nativeTest"]) }
        listOf("linuxX64", "linuxArm64", "mingwX64").forEach { target ->
            sourceSets["${target}Main"].dependsOn(nativeNonAppleMain)
            sourceSets["${target}Test"].dependsOn(nativeNonAppleTest)
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.zip4j)
            }
        }
        getByName("wasmJsMain") {
            dependencies {
                // pako 2.1.0 is the de-facto sync zlib in JS — feature-frozen, zero deps,
                // matches our PlatformDeflater/PlatformInflater contract 1:1 (raw/zlib/gzip
                // wbits, Z_NO_FLUSH/Z_FINISH, synchronous push). Pinned to exact version;
                // consumers should use a lockfile.
                implementation(npm("pako", "2.1.0"))
            }
        }
        getByName("appleTest") {
            kotlin.srcDir(generateAppleTestData.map {
                layout.buildDirectory.dir("generated/appleTestData/kotlin")
            })
        }
        nativeNonAppleTest.kotlin.srcDir(generateNativeNonAppleTestData.map {
            layout.buildDirectory.dir("generated/nativeNonAppleTestData/kotlin")
        })
        getByName("wasmJsTest") {
            kotlin.srcDir(generateWasmJsTestData.map {
                layout.buildDirectory.dir("generated/wasmJsTestData/kotlin")
            })
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-zip")
        description.set("Kotlin Multiplatform ZIP streams for JVM, iOS, macOS, Linux, Windows, and Wasm/JS")
        url.set("https://github.com/henrik242/kmp-zip")
        licenses {
            license {
                name.set("MPL-2.0")
                url.set("https://opensource.org/license/mpl-2-0")
            }
        }
        developers {
            developer {
                id.set("henrik242")
                url.set("https://github.com/henrik242")
            }
        }
        scm {
            url.set("https://github.com/henrik242/kmp-zip")
            connection.set("scm:git:git://github.com/henrik242/kmp-zip.git")
            developerConnection.set("scm:git:ssh://git@github.com/henrik242/kmp-zip.git")
        }
    }
}
