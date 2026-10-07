#!/usr/bin/env bash
# Builds the signed release APK as dist/sync-it.apk for the public releases repo
# (github.com/aritraroy02/sync-it-releases). Firebase's free plan can't host APKs, so the invite
# site links to .../releases/latest/download/sync-it.apk instead.
# Needs keystore.properties (release signing); see RUN_ON_MY_MACHINE.md.
# Windows: run it with `bash scripts/prepare-invite-site.sh` (PowerShell can't run .sh files).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f keystore.properties ] || { echo "keystore.properties missing: release APK would be unsigned." >&2; exit 1; }
./gradlew assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/sync-it.apk
ls -l dist/sync-it.apk
echo "Upload dist/sync-it.apk (keep that exact name) to a new release on github.com/aritraroy02/sync-it-releases."
echo "Site changes: firebase deploy --only hosting"
