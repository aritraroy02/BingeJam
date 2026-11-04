#!/usr/bin/env bash
# Publishes a new BingeJam version. Updates come only from the Indus Appstore (2026-11-04):
#   bash scripts/release.sh 0.7.0 "What changed, one line per item"
# 1. bumps versionName/versionCode in app/build.gradle.kts
# 2. runs the tests and builds the signed release APK (dist/sync-it-indus.apk, no GitHub updater)
# 3. commits "Release X.Y.Z" and tags vX.Y.Z here (not pushed)
# 4. uploads it to the Indus Appstore as an update (scripts/indus-upload.sh)
# GITHUB_RELEASE=1 also builds the old GitHub flavour (-Pstore=github) and creates a GitHub release
# in aritraroy02/sync-it-releases; off by default.
# Needs: keystore.properties (release key), the Indus API key (see indus-upload.sh), and gh only for
# GITHUB_RELEASE=1.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: release.sh X.Y.Z \"notes\"}"
NOTES="${2:?release notes required}"
REPO="aritraroy02/sync-it-releases"
GH="${GH:-gh}"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must be X.Y.Z" >&2; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing (release key)" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first" >&2; exit 1; }
GITHUB_RELEASE="${GITHUB_RELEASE:-0}"
if [ "$GITHUB_RELEASE" = "1" ]; then
  "$GH" auth status >/dev/null 2>&1 || { echo "Run: gh auth login" >&2; exit 1; }
  if "$GH" release view "v$VERSION" --repo "$REPO" >/dev/null 2>&1; then
    echo "v$VERSION already released on GitHub" >&2; exit 1
  fi
fi

GRADLE=app/build.gradle.kts
CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+')
NEXT=$((CODE + 1))
sed -i -E "s/versionCode = [0-9]+/versionCode = $NEXT/; s/versionName = \"[^\"]+\"/versionName = \"$VERSION\"/" "$GRADLE"
echo "Version $VERSION (code $NEXT)"

./gradlew test assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/sync-it-indus.apk

git add "$GRADLE"
git commit -q -m "Release $VERSION" -m "versionCode $NEXT."
git tag -a "v$VERSION" -m "BingeJam $VERSION"

bash scripts/indus-upload.sh "$NOTES" dist/sync-it-indus.apk

if [ "$GITHUB_RELEASE" = "1" ]; then
  ./gradlew assembleRelease -Pstore=github
  cp app/build/outputs/apk/release/app-release.apk dist/sync-it.apk
  "$GH" release create "v$VERSION" dist/sync-it.apk \
    --repo "$REPO" \
    --title "BingeJam $VERSION" \
    --notes "$NOTES"
  echo "GitHub release: https://github.com/$REPO/releases/tag/v$VERSION"
fi
echo "Released $VERSION to the Indus Appstore. Push the commit and tag: git push --follow-tags"
