plugins {
    application
}

repositories {
    mavenLocal { content { includeGroup("no.synth") } }
    mavenCentral { content { excludeGroup("no.synth") } }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(8)) }
}

dependencies {
    val version = providers.gradleProperty("kmpzipVersion").orNull?.takeIf { it.isNotBlank() }
        ?: error("pass -PkmpzipVersion=<version>")
    implementation("no.synth:kmp-zip:$version")
}

application {
    mainClass.set("Java8Consumer")
}
