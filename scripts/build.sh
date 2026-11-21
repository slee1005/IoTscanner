#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
scanner_java="${JAVA_HOME:-}"
if [ ! -x "$scanner_java/bin/java" ]; then
    if [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
        scanner_java=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    elif command -v java >/dev/null 2>&1; then
        scanner_java="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
fi
if [ ! -x "$scanner_java/bin/java" ]; then
    echo 'Set JAVA_HOME to an installed JDK 17 or newer.' >&2
    exit 1
fi
if [ "$#" -eq 0 ]; then
    set -- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
fi
JAVA_HOME="$scanner_java" ./gradlew "$@" --console=plain
