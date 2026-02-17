plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
}
