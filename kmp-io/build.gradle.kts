plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
}

group = "no.synth"
version = "0.6.3"

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

// Generate TestData.native.kt from template with the absolute resource path baked in
val generateNativeTestData by tasks.registering {
    val templateFile = layout.projectDirectory.file("src/nativeTest/kotlin/no/synth/kmpio/zip/TestData.native.kt.template")
    val outputDir = layout.buildDirectory.dir("generated/nativeTestData/kotlin")
    val resourceDir = layout.projectDirectory.dir("src/commonTest/resources/testdata")
    inputs.file(templateFile)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("no/synth/kmpio/zip")
        dir.mkdirs()
        val content = templateFile.asFile.readText()
            .replace("@@RESOURCE_DIR@@", resourceDir.asFile.absolutePath)
        dir.resolve("TestData.native.kt").writeText(content)
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
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-io")
        description.set("Kotlin Multiplatform ByteArrayInputStream and ZipInputStream for JVM and iOS")
        url.set("https://github.com/henrik242/kmp-io")
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
            url.set("https://github.com/henrik242/kmp-io")
            connection.set("scm:git:git://github.com/henrik242/kmp-io.git")
            developerConnection.set("scm:git:ssh://git@github.com/henrik242/kmp-io.git")
        }
    }
}
