plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

group = "no.synth.kmplibs"
version = "0.4.0"

kotlin {
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Generate TestData.native.kt with the absolute resource path baked in
val generateNativeTestData by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/nativeTestData/kotlin")
    val resourceDir = layout.projectDirectory.dir("src/commonTest/resources/testdata")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("no/synth/kmplibs/zip")
        dir.mkdirs()
        val resPath = resourceDir.asFile.absolutePath
        dir.resolve("TestData.native.kt").writeText("""
package no.synth.kmplibs.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object TestData {
    private const val RESOURCE_DIR = "$resPath"

    actual fun loadResource(name: String): ByteArray {
        val path = RESOURCE_DIR + "/" + name
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("Test resource not found: ${'$'}path")
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
        return bytes
    }

    actual val storedZip: ByteArray get() = loadResource("stored.zip")
    actual val deflatedZip: ByteArray get() = loadResource("deflated.zip")
    actual val multiEntryZip: ByteArray get() = loadResource("multi-entry.zip")
    actual val directoryZip: ByteArray get() = loadResource("directory.zip")
    actual val emptyZip: ByteArray get() = loadResource("empty.zip")
    actual val binaryZip: ByteArray get() = loadResource("binary.zip")
    actual val cliStoredZip: ByteArray get() = loadResource("cli-stored.zip")
    actual val cliDeflatedZip: ByteArray get() = loadResource("cli-deflated.zip")
    actual val cliWithDirZip: ByteArray get() = loadResource("cli-with-dir.zip")
    actual val cliMixedZip: ByteArray get() = loadResource("cli-mixed.zip")
    actual val sevenStoredZip: ByteArray get() = loadResource("seven-stored.zip")
    actual val sevenDeflatedZip: ByteArray get() = loadResource("seven-deflated.zip")
}
""".trimIndent())
    }
}

// Add the generated source to each iOS test source set
val generatedSrcDir = tasks.named("generateNativeTestData").map { layout.buildDirectory.dir("generated/nativeTestData/kotlin") }
listOf("iosX64Test", "iosArm64Test", "iosSimulatorArm64Test").forEach { name ->
    kotlin.sourceSets.getByName(name) {
        kotlin.srcDir(generatedSrcDir)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.environmentVariable("CI").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-libs")
        description.set("Kotlin Multiplatform ByteArrayInputStream and ZipInputStream for JVM and iOS")
        url.set("https://github.com/henrik242/kmp-libs")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("henrik242")
                url.set("https://github.com/henrik242")
            }
        }
        scm {
            url.set("https://github.com/henrik242/kmp-libs")
            connection.set("scm:git:git://github.com/henrik242/kmp-libs.git")
            developerConnection.set("scm:git:ssh://git@github.com/henrik242/kmp-libs.git")
        }
    }
}
