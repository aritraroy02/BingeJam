#!/usr/bin/env bash
# Builds the signed release APK and puts it at web/download/sync-it.apk for the invite site.
# Then publish with: firebase deploy --only hosting
# Needs keystore.properties (release signing); see RUN_ON_MY_MACHINE.md.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f keystore.properties ] || { echo "keystore.properties missing: release APK would be unsigned." >&2; exit 1; }
./gradlew assembleRelease
mkdir -p web/download
cp app/build/outputs/apk/release/app-release.apk web/download/sync-it.apk
ls -l web/download/sync-it.apk
echo "Ready. Publish with: firebase deploy --only hosting"
