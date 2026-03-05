plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "no.synth"
    version = "0.7.2"
}
