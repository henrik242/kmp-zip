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

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":kmp-zip"))
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
