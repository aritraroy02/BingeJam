#!/usr/bin/env bash
# Publishes a new Sync it version:
#   bash scripts/release.sh 0.3.0 "What changed, one line per item"
# 1. bumps versionName/versionCode in app/build.gradle.kts
# 2. runs the tests and builds the signed release APK (dist/sync-it.apk)
# 3. commits "Release X.Y.Z" and tags vX.Y.Z here (not pushed)
# 4. creates GitHub release vX.Y.Z in aritraroy02/sync-it-releases with sync-it.apk attached.
#    The invite site and the in-app update banner use .../releases/latest/download/sync-it.apk.
# Needs: keystore.properties (release key), gh logged in (gh auth status).
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: release.sh X.Y.Z \"notes\"}"
NOTES="${2:?release notes required}"
REPO="aritraroy02/sync-it-releases"
GH="${GH:-gh}"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must be X.Y.Z" >&2; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing (release key)" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first" >&2; exit 1; }
"$GH" auth status >/dev/null 2>&1 || { echo "Run: gh auth login" >&2; exit 1; }
if "$GH" release view "v$VERSION" --repo "$REPO" >/dev/null 2>&1; then
  echo "v$VERSION already released" >&2; exit 1
fi

GRADLE=app/build.gradle.kts
CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+')
NEXT=$((CODE + 1))
sed -i -E "s/versionCode = [0-9]+/versionCode = $NEXT/; s/versionName = \"[^\"]+\"/versionName = \"$VERSION\"/" "$GRADLE"
echo "Version $VERSION (code $NEXT)"

./gradlew test assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/sync-it.apk

git add "$GRADLE"
git commit -q -m "Release $VERSION" -m "versionCode $NEXT."
git tag -a "v$VERSION" -m "Sync it $VERSION"

"$GH" release create "v$VERSION" dist/sync-it.apk \
  --repo "$REPO" \
  --title "Sync it $VERSION" \
  --notes "$NOTES"

echo "Released: https://github.com/$REPO/releases/tag/v$VERSION"
