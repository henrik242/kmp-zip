#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ $# -eq 0 ]; then
    exec "$SCRIPT_DIR/gradlew" -q :kmp-zip-cli:jvmRun --args="help"
fi

cmd="$1"
shift
exec "$SCRIPT_DIR/gradlew" -q :kmp-zip-cli:jvmRun --args="$cmd --cwd $(pwd) $*"
