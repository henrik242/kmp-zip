plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dependency.updates)
}

allprojects {
    group = "no.synth"
    version = "0.11.1"
}
