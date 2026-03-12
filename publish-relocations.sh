#!/usr/bin/env bash
#
# Publishes empty "relocation" artifacts to Maven Central for old kmp-io / kmplibs
# platform-specific artifacts that are missing redirect notices.
#
# Prerequisites:
#   - .env file with: MAVEN_CENTRAL_USERNAME, MAVEN_CENTRAL_PASSWORD,
#     GPG_SIGNING_KEY, GPG_SIGNING_PASSWORD
#
# Usage:
#   ./publish-relocations.sh            # publish all relocation artifacts
#   ./publish-relocations.sh --dry-run  # only generate modules, don't publish
#
set -euo pipefail

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
fi

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Load credentials from .env ───────────────────────────────────────────────
ENV_FILE="$PROJECT_DIR/.env"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
  export ORG_GRADLE_PROJECT_mavenCentralUsername="$MAVEN_CENTRAL_USERNAME"
  export ORG_GRADLE_PROJECT_mavenCentralPassword="$MAVEN_CENTRAL_PASSWORD"
  # Gradle's env var handling doesn't replace \n with newlines (unlike .properties files),
  # so we must convert literal \n sequences to real newlines for the PGP key.
  export ORG_GRADLE_PROJECT_signingInMemoryKey
  ORG_GRADLE_PROJECT_signingInMemoryKey="$(printf '%s' "$GPG_SIGNING_KEY" | sed 's/\\n/\n/g')"
  export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$GPG_SIGNING_PASSWORD"
else
  echo "Error: $ENV_FILE not found. Create it with MAVEN_CENTRAL_USERNAME, MAVEN_CENTRAL_PASSWORD, GPG_SIGNING_KEY, GPG_SIGNING_PASSWORD."
  exit 1
fi
RELOCATION_VERSION="0.7.0"

PLATFORMS=(iosarm64 iossimulatorarm64 iosx64 jvm)

# ── Define relocations ───────────────────────────────────────────────────────
# Format: oldGroup:oldArtifact:newGroup:newArtifact
RELOCATIONS=()

for platform in "${PLATFORMS[@]}"; do
  RELOCATIONS+=("no.synth:kmp-io-${platform}:no.synth:kmp-zip-${platform}")
  RELOCATIONS+=("no.synth:kmp-io-kotlinx-${platform}:no.synth:kmp-zip-kotlinx-${platform}")
  RELOCATIONS+=("no.synth.kmplibs:library-${platform}:no.synth:kmp-zip-${platform}")
done

# ── Backup original settings ─────────────────────────────────────────────────
SETTINGS_FILE="$PROJECT_DIR/settings.gradle.kts"
cp "$SETTINGS_FILE" "$SETTINGS_FILE.bak"

cleanup() {
  echo ""
  echo "── Cleaning up ──────────────────────────────────────────────────"
  mv "$SETTINGS_FILE.bak" "$SETTINGS_FILE"
  for reloc in "${RELOCATIONS[@]}"; do
    IFS=: read -r oldGroup oldArtifact _ _ <<< "$reloc"
    module_dir="$PROJECT_DIR/relocation-${oldGroup//\./-}-${oldArtifact}"
    rm -rf "$module_dir"
  done
  echo "Restored settings.gradle.kts and removed relocation modules."
}
trap cleanup EXIT

# ── Generate relocation modules ──────────────────────────────────────────────
INCLUDES=""

for reloc in "${RELOCATIONS[@]}"; do
  IFS=: read -r oldGroup oldArtifact newGroup newArtifact <<< "$reloc"

  module_name="relocation-${oldGroup//\./-}-${oldArtifact}"
  module_dir="$PROJECT_DIR/$module_name"

  mkdir -p "$module_dir"

  cat > "$module_dir/build.gradle.kts" <<GRADLE
plugins {
    \`java-library\`
    alias(libs.plugins.maven.publish)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("$oldGroup", "$oldArtifact", "$RELOCATION_VERSION")

    pom {
        name.set("$oldArtifact (relocated to $newGroup:$newArtifact)")
        description.set("This artifact has been relocated to $newGroup:$newArtifact")
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
        withXml {
            asNode().appendNode("distributionManagement").apply {
                appendNode("relocation").apply {
                    appendNode("groupId", "$newGroup")
                    appendNode("artifactId", "$newArtifact")
                    appendNode("message", "This artifact has moved to $newGroup:$newArtifact")
                }
            }
        }
    }
}
GRADLE

  INCLUDES="$INCLUDES
include(\":$module_name\")"

  echo "Created module: $module_name ($oldGroup:$oldArtifact → $newGroup:$newArtifact)"
done

# ── Update settings.gradle.kts ───────────────────────────────────────────────
cat >> "$SETTINGS_FILE" <<SETTINGS
$INCLUDES
SETTINGS

echo ""
echo "── Generated ${#RELOCATIONS[@]} relocation modules ──────────────────────"
echo ""

if $DRY_RUN; then
  echo "Dry run mode — skipping publish. Modules are in place for inspection."
  echo "To publish manually:  ./gradlew publishAllPublicationsToMavenCentralRepository"
  echo ""
  echo "Press Enter to clean up..."
  read -r
  exit 0
fi

# ── Publish ──────────────────────────────────────────────────────────────────
echo "Publishing relocation artifacts to Maven Central..."
echo ""

# Build the list of publish tasks for just the relocation modules
TASKS=()
for reloc in "${RELOCATIONS[@]}"; do
  IFS=: read -r oldGroup oldArtifact _ _ <<< "$reloc"
  module_name="relocation-${oldGroup//\./-}-${oldArtifact}"
  TASKS+=(":${module_name}:publishAllPublicationsToMavenCentralRepository")
done

"$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" "${TASKS[@]}"

echo ""
echo "── Done! All relocation artifacts published. ─────────────────────"
