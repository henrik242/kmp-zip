import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "wasmjs-demo.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        getByName("wasmJsMain") {
            dependencies {
                implementation(project(":kmp-zip"))
            }
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
        }
    }
}
