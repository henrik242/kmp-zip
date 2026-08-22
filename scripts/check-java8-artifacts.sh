#!/usr/bin/env bash
# Fail unless the published JVM jars hold only Java 8 bytecode and their Gradle
# metadata declares org.gradle.jvm.version = 8. Run after `gradlew build` plus
# publishToMavenLocal or generateMetadataFileForJvmPublication.
set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for m in kmp-zip kmp-zip-okio kmp-zip-kotlinx; do
  # *[0-9].jar is the plain artifact, skipping -sources.jar and -javadoc.jar. A stale
  # jar from an earlier version would make unzip treat the second match as a filter.
  set -- "$m"/build/libs/*-jvm-*[0-9].jar
  [ $# -eq 1 ] || { echo "FAIL: expected one $m jvm jar, found $#: $*" >&2; exit 1; }
  unzip -qo "$1" '*.class' -d "$tmp/$m"
  grep -q '"org.gradle.jvm.version": 8' "$m/build/publications/jvm/module.json" \
    || { echo "FAIL: $m metadata does not declare org.gradle.jvm.version 8" >&2; exit 1; }
done

majors=$(find "$tmp" -name '*.class' -exec javap -verbose {} + | awk '/major version:/ {print $3}' | sort -un)
[ "$majors" = "52" ] || { echo "FAIL: class major versions present: $(echo "$majors" | tr '\n' ' ')" >&2; exit 1; }
echo "OK: Java 8 bytecode (major 52) and metadata"
