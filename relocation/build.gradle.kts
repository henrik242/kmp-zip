plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "no.synth.kmplibs"
version = "0.6.1"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("no.synth.kmplibs", "library", version.toString())

    pom {
        name.set("library (relocated to no.synth:kmp-io)")
        description.set("This artifact has been relocated to no.synth:kmp-io")
        url.set("https://github.com/henrik242/kmp-io")
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
            url.set("https://github.com/henrik242/kmp-io")
            connection.set("scm:git:git://github.com/henrik242/kmp-io.git")
            developerConnection.set("scm:git:ssh://git@github.com/henrik242/kmp-io.git")
        }
        withXml {
            asNode().appendNode("distributionManagement").apply {
                appendNode("relocation").apply {
                    appendNode("groupId", "no.synth")
                    appendNode("artifactId", "kmp-io")
                    appendNode("message", "This artifact has moved to no.synth:kmp-io")
                }
            }
        }
    }
}
