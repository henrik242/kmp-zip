import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    jvm()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    // js and wasmJs both register browser and Node so consumers see explicit
    // support for each, but the browser test tasks are disabled: running them
    // would try to download a headless Chromium onto CI runners.
    js {
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// ZlibBenchmark only runs with -Pkmpzlib.bench, and then alone.
val bench = providers.gradleProperty("kmpzlib.bench").isPresent
tasks.withType<AbstractTestTask>().configureEach {
    filter.apply {
        // Only needed for bench runs, where most test tasks have nothing to include.
        // Elsewhere a mistyped --tests filter should still fail.
        if (bench) isFailOnNoMatchingTests = false
        if (bench) includeTestsMatching("*ZlibBenchmark*") else excludeTestsMatching("*ZlibBenchmark*")
    }
    if (bench) {
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false }
    }
}
if (bench) {
    tasks.withType<KotlinJsTest>().configureEach { useMocha { timeout = "0" } }
}

// The MIT and zlib licenses require their notices to travel with the code.
tasks.withType<Zip>().matching { it.archiveExtension.get() == "jar" }.configureEach {
    from("LICENSE") { into("META-INF") }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-zlib")
        description.set("Pure-Kotlin zlib (DEFLATE, zlib and gzip streams) for all Kotlin Multiplatform targets, ported from pako")
        url.set("https://github.com/henrik242/kmp-zip")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
            }
            license {
                name.set("Zlib")
                url.set("https://opensource.org/license/zlib")
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
