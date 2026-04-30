plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
}

// Generate platform-specific TestData files from templates with the absolute resource
// path baked in. Backslashes in the absolute path (Windows) are normalized to forward
// slashes — both because backslashes in a Kotlin string literal would be interpreted
// as escape sequences (corrupting paths containing e.g. `\U` or `\t`), and because
// fopen on Windows accepts mixed-slash paths.
val resourceDir = layout.projectDirectory.dir("src/commonTest/resources/testdata")
val resourceDirPath = resourceDir.asFile.absolutePath.replace('\\', '/')

val generateAppleTestData by tasks.registering {
    val templateFile = layout.projectDirectory.file("src/appleTest/kotlin/no/synth/kmpzip/zip/TestData.apple.kt.template")
    val outputDir = layout.buildDirectory.dir("generated/appleTestData/kotlin")
    inputs.file(templateFile)
    inputs.property("resourceDir", resourceDirPath)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("no/synth/kmpzip/zip")
        dir.mkdirs()
        val content = templateFile.asFile.readText()
            .replace("@@RESOURCE_DIR@@", resourceDirPath)
        dir.resolve("TestData.apple.kt").writeText(content)
    }
}

val generateNativeNonAppleTestData by tasks.registering {
    val templateFile = layout.projectDirectory.file("src/nativeNonAppleTest/kotlin/no/synth/kmpzip/zip/TestData.nativeNonApple.kt.template")
    val outputDir = layout.buildDirectory.dir("generated/nativeNonAppleTestData/kotlin")
    inputs.file(templateFile)
    inputs.property("resourceDir", resourceDirPath)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("no/synth/kmpzip/zip")
        dir.mkdirs()
        val content = templateFile.asFile.readText()
            .replace("@@RESOURCE_DIR@@", resourceDirPath)
        dir.resolve("TestData.nativeNonApple.kt").writeText(content)
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

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val nativeNonAppleMain by creating { dependsOn(sourceSets["nativeMain"]) }
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
        getByName("appleTest") {
            kotlin.srcDir(generateAppleTestData.map {
                layout.buildDirectory.dir("generated/appleTestData/kotlin")
            })
        }
        nativeNonAppleTest.kotlin.srcDir(generateNativeNonAppleTestData.map {
            layout.buildDirectory.dir("generated/nativeNonAppleTestData/kotlin")
        })
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-zip")
        description.set("Kotlin Multiplatform ZIP streams for JVM, iOS, macOS, Linux, and Windows")
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
