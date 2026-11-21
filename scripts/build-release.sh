#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f signing.properties ]; then
    echo 'Create signing.properties from signing.properties.example and supply a release upload key.' >&2
    exit 1
fi
./scripts/build.sh :app:bundleRelease :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease "$@"
scanner_java="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
scanner_bundle=app/build/outputs/bundle/release/app-release.aab
if ! unzip -Z1 "$scanner_bundle" | rg -q '^META-INF/[^/]+\.RSA$'; then
    echo 'Release bundle is unsigned; refusing to prepare it for upload.' >&2
    exit 1
fi
mkdir -p build
if ! "$scanner_java/bin/jarsigner" -verify "$scanner_bundle" > build/release-signature-verification.txt 2>&1; then
    cat build/release-signature-verification.txt >&2
    exit 1
fi
echo 'Release bundle signature verified.'
echo "Signed release bundle: $scanner_bundle"
