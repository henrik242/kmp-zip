import java.time.Duration
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

val testJvm: String? = providers.gradleProperty("test.jvm").orNull

allprojects {
    group = "no.synth"
    version = "1.0.0"

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        // Published modules only. kmp-zip-cli ships as native binaries, and its JVM jar
        // is a local convenience, so it tracks the build JDK instead.
        plugins.withId("com.vanniktech.maven.publish") {
            extensions.configure<KotlinMultiplatformExtension> {
                targets.withType<KotlinJvmTarget>().configureEach {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_1_8)
                        freeCompilerArgs.add("-Xjdk-release=1.8")
                    }
                    // KGP derives org.gradle.jvm.version from the Gradle JDK, not from
                    // jvmTarget, so this is what lets Java 8 consumers resolve. It also
                    // holds jvmTest, and every jvm classpath, to Java 8.
                    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8) }
                }
            }

            if (testJvm != null) {
                val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(testJvm))
                }
                tasks.withType<Test>().configureEach { javaLauncher.set(launcher) }
            }
        }
    }

    // Backstop against a runaway test process. A synchronous spin in js/wasmJs
    // test code blocks the single JS thread, so the runner's own timeout can
    // never fire (KGP passes mocha --timeout 2s and the timer just never runs),
    // and the node process then outlives the build, burning cores until it is
    // killed by hand. A task timeout fails the task and reaps the child. The
    // whole suite runs in well under a minute, so this only ever trips on a hang.
    tasks.withType<AbstractTestTask>().configureEach {
        timeout.set(Duration.ofMinutes(10))
    }

    tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>().configureEach {
        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}
