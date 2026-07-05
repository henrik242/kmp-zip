import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":kmp-zip"))
                implementation(libs.okio)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.okio)
                implementation(libs.okio.fakefilesystem)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Tests that need a separate thread to fire timers while a busy body runs
        // (e.g. `withTimeout` cancelling an in-flight zip). wasmJs is single-threaded
        // — the timer can't fire until the body suspends — so it's excluded here.
        val multiThreadedTest = create("multiThreadedTest") { dependsOn(commonTest.get()) }
        jvmTest { dependsOn(multiThreadedTest) }
        nativeTest { dependsOn(multiThreadedTest) }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-zip-okio")
        description.set("OkIO BufferedSource/BufferedSink adapters for kmp-zip ZipInputStream and ZipOutputStream")
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
