import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        mainRun {
            mainClass.set("no.synth.kmpzip.cli.MainKt")
        }
    }
    macosArm64 { binaries { executable { entryPoint = "no.synth.kmpzip.cli.main"; baseName = "kmpzip-macos-arm64" } } }
    macosX64 { binaries { executable { entryPoint = "no.synth.kmpzip.cli.main"; baseName = "kmpzip-macos-x64" } } }
    linuxX64 { binaries { executable { entryPoint = "no.synth.kmpzip.cli.main"; baseName = "kmpzip-linux-x64" } } }
    linuxArm64 { binaries { executable { entryPoint = "no.synth.kmpzip.cli.main"; baseName = "kmpzip-linux-arm64" } } }
    mingwX64 { binaries { executable { entryPoint = "no.synth.kmpzip.cli.main"; baseName = "kmpzip-windows-x64" } } }

    // Kotlin/Native hardcodes the .kexe extension on non-Windows targets; copy
    // the link output to a sibling without the extension so the binary is named
    // `kmpzip-...`. The original .kexe is left in place to keep Gradle's
    // up-to-date check happy.
    targets.withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family != Family.MINGW }
        .configureEach {
            binaries.withType<Executable>().configureEach {
                val binary = this
                linkTaskProvider.configure {
                    doLast {
                        val src = binary.outputFile
                        if (src.exists()) {
                            src.copyTo(src.resolveSibling(src.nameWithoutExtension), overwrite = true)
                                .setExecutable(true)
                        }
                    }
                }
            }
        }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":kmp-zip"))
                implementation(project(":kmp-zip-kotlinx"))
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.io.core)
            }
        }
    }
}
