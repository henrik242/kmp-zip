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
        // The default-dispatcher actual is identical on native and on js/wasmJs;
        // only jvm differs. Named to match kmp-zip's own commonNonJvmMain.
        val commonNonJvmMain = create("commonNonJvmMain") { dependsOn(commonMain.get()) }
        sourceSets["nativeMain"].dependsOn(commonNonJvmMain)
        sourceSets["webMain"].dependsOn(commonNonJvmMain)

        commonMain {
            dependencies {
                api(project(":kmp-zip"))
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kmp-zip-kotlinx")
        description.set("kotlinx-io Source/Sink adapters for kmp-zip ZipInputStream and ZipOutputStream")
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
