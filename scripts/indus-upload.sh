#!/usr/bin/env bash
# Uploads a new BingeJam version to the Indus Appstore (updates only: the app must already be
# published once by hand in the Indus developer console).
#   bash scripts/indus-upload.sh "release notes" [path/to/app.apk]
# API (from PhonePe's indus-flows action): POST https://developer-api.indusappstore.com/devtools/apk/upgrade/<package>
#   Authorization: O-Bearer <token>   multipart: file=@<apk>, releaseNotes=<text>
# Token: Indus console -> Tools & Resources -> API token. Kept outside the repo in
# ~/.syncit/indus-api.key (the whole header value, "O-Bearer ...") or in $INDUS_API_KEY. Never commit it.
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:?usage: indus-upload.sh \"release notes\" [apk]}"
APK="${2:-dist/sync-it.apk}"
PACKAGE="com.syncit.app"
URL="https://developer-api.indusappstore.com/devtools/apk/upgrade/$PACKAGE"
KEY_FILE="${INDUS_API_KEY_FILE:-$HOME/.syncit/indus-api.key}"

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
if [ -n "${INDUS_API_KEY:-}" ]; then KEY="$INDUS_API_KEY"
elif [ -f "$KEY_FILE" ]; then KEY="$(tr -d '\r\n' < "$KEY_FILE")"
else echo "No Indus API key: set INDUS_API_KEY or create $KEY_FILE" >&2; exit 1; fi
case "$KEY" in O-Bearer\ *) ;; *) KEY="O-Bearer $KEY" ;; esac

BODY="$(mktemp)"; trap 'rm -f "$BODY"' EXIT
echo "Uploading $APK to Indus Appstore ($PACKAGE)..."
# --form-string so notes starting with @ or < are never read as files.
STATUS=$(curl -sS -o "$BODY" -w "%{http_code}" -X POST \
  -H "Authorization: $KEY" \
  -F "file=@$APK" \
  --form-string "releaseNotes=$NOTES" \
  "$URL")
RESPONSE="$(head -c 2000 "$BODY")"

# Indus has answered a malformed upload with HTTP 200 + an error text, so check both.
if [[ "$STATUS" =~ ^2 ]] && ! grep -qiE '"error|errorCode|unexpected|invalid|fail' "$BODY"; then
  echo "Indus: uploaded (HTTP $STATUS). $RESPONSE"
else
  echo "Indus upload FAILED (HTTP $STATUS): $RESPONSE" >&2
  echo "If the app isn't live on Indus yet, publish the first version in the console first." >&2
  exit 1
fi
