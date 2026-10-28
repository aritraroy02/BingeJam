# Setup, build and test

Everything needed to set up Firebase, build, install and test Sync it, grouped by phase.
The app hasn't been built or run yet. Report errors back and they get routed to the owning
module and re-reviewed.

Commands are for a bash-like shell from the project root (`sync-it/`). On Windows PowerShell,
use `.\gradlew.bat` instead of `./gradlew`.

---

## One-time setup

### Tools
Already installed on the main dev machine (Windows, no admin needed, user env vars set):
- JDK 21 (Temurin): `%LOCALAPPDATA%\Programs\devtools\jdk-21.0.12.1+1` (`JAVA_HOME`)
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (`ANDROID_HOME`) with platform 36, build-tools
  35/36, platform-tools (`adb` on PATH). Android Studio, if installed later, finds the same SDK.
- `local.properties` (git-ignored) points `sdk.dir` there.

On a new machine: install Android Studio (it bundles a JDK and the SDK), or a JDK 17+ plus the
Android command-line tools, then `sdkmanager "platform-tools" "platforms;android-36"`.

**Two dev machines (since 2026-10-28):** the main dev machine (`C:\Users\aritr`, has the release key)
and the work laptop (`C:\Users\AritraAditya`, set up the same way: JDK 21 in
`%LOCALAPPDATA%\Programs\devtools`, SDK in `%LOCALAPPDATA%\Android\Sdk`, user env vars set,
`google-services.json` via `firebase apps:sdkconfig`). To keep them interchangeable:
- `git pull` before working, push when done (release.sh bumps versionCode; never release from a stale checkout).
- Same debug key on both: copy the main machine's `~/.android/debug.keystore` to the other, so debug
  builds install over each other and match the fingerprints registered in Firebase / assetlinks.json.
- To release from the laptop too: copy `syncit-release.jks` + `keystore.properties` by hand (never git,
  chat or email) and fix `storeFile=` in that machine's `keystore.properties`.

The Gradle wrapper (8.13) is committed; use `./gradlew` / `.\gradlew.bat`.

### Dependency versions
`gradle/libs.versions.toml` resolves and builds as-is. Bumping is optional. Kotlin 2.x needs
the matching `org.jetbrains.kotlin.plugin.compose` version (same as `kotlin`).

### Firebase setup
**Done (2026-10-06):** project `syncit-watchparty`, Realtime Database in Singapore, Anonymous
auth, Android app `com.syncit.app`, `google-services.json`, `DATABASE_URL`. Rules are published
from the repo root with the Firebase CLI (`npm i -g firebase-tools`, `firebase login`):

```bash
firebase deploy --only database     # uses firebase.json + .firebaserc
```

Re-run it whenever `database.rules.json` changes. The steps below are for setting up a new project.
1. https://console.firebase.google.com → **Add project** (e.g. `sync-it`). Analytics: optional, not used.
2. **Build → Authentication → Get started → Sign-in method → Anonymous → Enable.**
3. **Build → Realtime Database → Create database.** Location: **Singapore (asia-southeast1)**,
   the closest to India. Start in **locked mode**.
4. **Realtime Database → Rules**: paste the contents of
   `data-firebase/firebase/database.rules.json` and **Publish**.
5. **Project settings → Your apps → Add app → Android.** Package name: `com.syncit.app`.
   (SHA-1 not needed for anonymous auth.) Download `google-services.json`.
6. Put it at **`app/google-services.json`** (it's in `.gitignore`). See `app/google-services.json.README.md`.
7. Copy the database URL from the Realtime Database page (looks like
   `https://<project>-default-rtdb.asia-southeast1.firebasedatabase.app`) into
   `FirebaseConfig.DATABASE_URL` in
   `data-firebase/src/main/kotlin/com/syncit/firebase/FirebaseConfig.kt`.
   (Needed because non-US databases aren't always in `google-services.json`.)

---

### Release signing + invite site (done 2026-10-07)
- Release key: `C:/Users/aritr/.syncit/syncit-release.jks` (PKCS12, alias `syncit`), passwords in
  `keystore.properties` at the repo root (git-ignored; a copy is in `.syncit/keystore.properties.backup`).
  **Back both files up somewhere safe.** Losing them means installed copies can't be updated, and the
  App Links fingerprint in `web/.well-known/assetlinks.json` changes.
  SHA-256: `E8:89:BE:C7:7C:C0:92:BE:B7:28:98:F4:6D:6B:5F:C8:CE:14:7C:AF:85:5C:63:F4:BF:B6:E5:2A:E2:31:33:5F`
- Invite site = `web/` on Firebase Hosting (`https://syncit-watchparty.web.app`): `/join/CODE` page
  and `/.well-known/assetlinks.json` (release + this machine's debug key).
- The APK is **not** on Firebase (the free Spark plan rejects executables). It's a release asset of
  the public repo `github.com/aritraroy02/sync-it-releases` (GitHub releases); the site links to
  `.../releases/latest/download/sync-it.apk`, so the file name must stay `sync-it.apk`.
- New app version (one command; needs `gh auth login` once):
  ```bash
  bash scripts/release.sh 0.3.0 "What changed"
  ```
  Bumps versionName/versionCode, runs tests, builds the signed APK, commits + tags `v0.3.0`
  here, and creates GitHub release `v0.3.0` in `sync-it-releases` with `sync-it.apk` attached.
  Installed apps show an "update available" banner on Home (UpdateChecker, GitHub API).
- Rules or site changes: `firebase deploy --only database,hosting`
- Check App Links on a phone: `adb shell pm verify-app-links --re-verify com.syncit.app`, then
  `adb shell pm get-app-links com.syncit.app` should show `syncit-watchparty.web.app: verified`.

---

### Push notifications (messages + friend requests), free
FCM is free on the Spark plan; only *sending* needs a trusted server, and Cloud Functions need
Blaze. So sending is done by `push-relay/`, a Cloudflare Worker on Cloudflare's free plan
(100,000 requests/day, no card). One-time setup:

1. **Service account key** (gives admin access, treat it like a password):
   Firebase console → Project settings → **Service accounts** → **Generate new private key**.
   Save the JSON **outside the repo** (e.g. `C:\Users\<you>\.syncit\service-account.json`).
2. **Cloudflare**: sign up free at https://dash.cloudflare.com/sign-up, then:
   ```bash
   npm i -g wrangler
   wrangler login
   cd push-relay
   wrangler secret put FIREBASE_SERVICE_ACCOUNT < C:/Users/<you>/.syncit/service-account.json
   wrangler deploy
   ```
   `deploy` prints the URL, e.g. `https://vyro-push-relay.<subdomain>.workers.dev`.
   **Windows PowerShell:** don't pipe the key with `Get-Content | wrangler ...`. PowerShell 5.1 adds
   a byte-order mark and the relay then fails with `Unexpected token '﻿'` (502). Use
   `cmd /c "npx wrangler secret put FIREBASE_SERVICE_ACCOUNT < path\to\key.json"`.
3. Put that URL in `FirebaseConfig.PUSH_RELAY_URL`
   (`data-firebase/src/main/kotlin/com/syncit/firebase/FirebaseConfig.kt`).
4. Publish the rules (new `pushTokens` node): `firebase deploy --only database`.
5. Build, install on two phones, sign in on both (each phone registers its token).
   Check: Firebase console → Realtime Database → `pushTokens/<uid>` has an entry per phone.
6. Test: on phone A, close the app (swipe it away is fine; **not** "Force stop"). From phone B
   send A a friend request, then a message once you're friends. A gets a notification within a
   few seconds; tapping it opens Friends / the chat.

If nothing arrives: `wrangler tail` (in `push-relay/`) shows the relay's logs live. 401 = the
app's ID token was rejected (check `FIREBASE_PROJECT_ID` in `wrangler.toml`); "FCM send failed"
= check that **Firebase Cloud Messaging API (V1)** is enabled (Project settings → Cloud
Messaging). On OPPO/ColorOS also allow the app to auto-launch / run in background, or
notifications can be held back while it's closed.

---

## Phase 0: diagnostics screen

### Build & install
```bash
./gradlew :app:assembleDebug
adb devices                                  # phone must show as "device" (enable USB debugging)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Use the diagnostics screen
1. Open Sync it. On the onboarding screen, tap **Open settings** under Notification Access and
   turn on **Sync it media control**. Come back. Step 1 shows ✓. Enter a name, **Continue**.
2. On Home tap **Media diagnostics**.
3. Open Netflix, start any title, let it play ~10 s, switch back to Sync it.
4. A card for `com.netflix.mediaclient` should be listed. Write down:
   - `state` while playing and while paused,
   - whether `position (reported)` changes when you pause at different points
     (0 or never changing → timeline sync will be turned off for that app),
   - whether `position (live)` tracks the movie while playing,
   - `actions`: especially **PLAY, PAUSE, PLAY_PAUSE, SEEK_TO**.
5. Tap the Netflix card to select it, then **Pause**, **Play**, **Seek +30s**. Switch to Netflix
   to confirm each one took effect.
6. Repeat 3–5 with **JioHotstar**. The card's package name is the JioHotstar package (see below).

### Cross-check with adb
```bash
adb shell dumpsys media_session
# Look for blocks like:
#   <tag> <package>/<...> (userId=0)
#     package=<package name>
#     active=true
#     state=PlaybackState {state=3, position=123456, buffered position=..., speed=1.0,
#                          updated=<elapsedRealtime>, actions=<decimal bitmask>, ...}
adb logcat -s SyncItDiag      # our own log of every session change, while the app is open
```
`actions` in dumpsys is decimal. Decode bits: PLAY=4, PAUSE=2, PLAY_PAUSE=512, SEEK_TO=256,
STOP=1, SKIP_TO_NEXT=32, SKIP_TO_PREVIOUS=16.

### Phase 0: find the JioHotstar package name
1. With JioHotstar playing, read the package from the diagnostics card or
   `adb shell dumpsys media_session | grep package=`.
2. Cross-check: `adb shell pm list packages | grep -i hotstar`.
3. Put it in `core/src/main/kotlin/com/syncit/core/config/StreamingConfig.kt`:
   `const val JIOHOTSTAR_PACKAGE = "<the package>"`, and delete the TODO.
4. Report back for both apps: package, actions bitmask, whether position updates on pause,
   whether play/pause/seek from the diagnostics screen worked.

---

## Phase 1: onboarding, rooms and presence

Requires the Firebase setup above (Anonymous auth on, rules published, `google-services.json` in place).

```bash
./gradlew :app:assembleDebug
adb -s <PHONE_A_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <PHONE_B_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```
(`adb devices` lists serials. With one phone on USB at a time, drop `-s`.)

Check:
1. Fresh install → onboarding. Each step turns ✓ after granting in settings and coming back.
   Battery step: after "Don't optimize", it turns ✓.
2. Phone A: **Create a room** → 6-character code shown. **Share invite** → send to phone B.
3. Phone B: tap the `syncit://join/CODE` link (or type the code) → **Join room**.
4. Both phones list both people with green dots; host tagged on A.
5. Turn on airplane mode on B → within ~1 min A shows B grey/"offline" (server-side onDisconnect).
   Turn it off → B goes green again.
6. Wrong code → "No room with that code".
7. Firebase console → Realtime Database → Data: `rooms/<CODE>/members/<uid>` has `online` flipping.

If room creation fails with "Permission denied", the rules weren't published or Anonymous
auth is off. If every write fails, check `FirebaseConfig.DATABASE_URL`.

---

## Phases 2–5: build, lint, unit tests

From the project root, in this order:

```bash
# 1. Pure-Kotlin sync engine unit tests (fake clock; no device needed)
./gradlew :sync-engine:test
#    Report: sync-engine/build/reports/tests/test/index.html

# 2. Compile everything
./gradlew assembleDebug

# 3. Android lint
./gradlew lint
#    Reports: app/build/reports/lint-results-debug.html (and per module)

# 4. All JVM unit tests across modules
./gradlew test
```

Send back the first error of any step that fails (plus the failing test names for step 1).

Known places to look if something fails:
- `state` writes rejected by rules → the `seq` rule was verified against `ServerValue.increment`
  in the emulator, so check that the published rules match `database.rules.json` and that
  the writer is in `members`.
- `gradlew clean` fails with "Unable to delete ... lint-cache" (Windows) → `gradlew --stop`, retry.
- Lint `MissingPermission` on `SyncForegroundService.notifyIfAllowed`: intentionally
  suppressed (guarded by `areNotificationsEnabled()`).
- Lint "ForegroundServiceType"/Play policy warnings about `mediaPlayback`: see
  PLAY_STORE_READINESS.md.

## Install on two phones

```bash
./gradlew :app:assembleDebug
adb devices
adb -s <PHONE_A_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <PHONE_B_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```
Both phones: complete onboarding (Notification Access, notifications, battery: Don't optimize).
Netflix on both, with a plan that allows 2 simultaneous screens (or two accounts).
Emulators usually can't run Netflix; use physical phones.

Useful while testing:
```bash
adb -s <SERIAL> logcat -s SyncItDiag SyncItCoordinator SyncItGateway
adb -s <SERIAL> shell dumpsys media_session
```

---

## Manual test checklist (two physical phones)

Setup for every test: A creates a room, B joins. Host sets the title (Netflix share link: in
Netflix tap Share on a title → copy link) → both tap **Open movie**, confirm the same title,
let it start. Status card on both shows **Syncing**. Turn on **Drift (debug)** on one phone.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| 1 | Play/pause A → B | A pauses in Netflix | B pauses within ~1 s at about the same frame; no bouncing (each pauses once) |
| 2 | Play/pause B → A | B plays | A plays within ~1 s |
| 3 | Rapid toggles | A taps pause/play 5× quickly | Both end in the same state; no endless toggling |
| 4 | Scrub forward A | A drags the timeline +5 min | B jumps to the same spot within ~2 s |
| 5 | Scrub back B | B rewinds 2 min | A follows |
| 6 | Small skip | A uses Netflix's 10 s skip | B follows (10 s > 3 s threshold) |
| 7 | Drift | Let it play 10 min | Drift overlay stays within ±1.5 s; no visible stutter from constant corrections |
| 8 | Join mid-movie | Room playing 10 min in; C (or B after leaving) joins and opens the title | C seeks to the room position, then plays |
| 9 | Join while paused | Pause room; C joins | C ends paused at the room position |
| 10 | Kill & reopen Netflix | On B: swipe Netflix away from recents | B status: "Netflix closed on this phone". Reopen the title on B → it seeks to the room position and matches play/pause; A unaffected |
| 11 | Netflix autoplay | B reopens Netflix and it autoplays from its resume point | B is pulled to the room, A is NOT moved |
| 12 | Network loss 30 s | Airplane mode on B for 30 s while playing; A pauses meanwhile | A shows B offline. When B reconnects, B pauses to match A |
| 13 | Network loss, local action | Airplane on B, B pauses, wait 30 s, airplane off | After reconnect the room follows the last write; both end in the same state |
| 14 | Screen off | B turns screen off for 2 min while A keeps playing, then on | B still in sync (or corrects within ~5 s); sync notification still present |
| 15 | Stop from notification | B taps **Stop syncing** in the notification | B stops following; status "Not in a room" + **Resume syncing** works |
| 15a | Quit from notification | B taps **Quit BingeJam** in the sync notification | Notification and chat bubble disappear, app leaves recents; A shows B offline within ~1 min; friends see B offline. `adb shell dumpsys activity services com.syncit.app` lists no MediaListenerService/SyncForegroundService. A DM to B still notifies. Opening the app brings everything back (Media diagnostics lists sessions again) |
| 15b | No ghost notification | B taps **Stop syncing** (or leaves the room) | The sync notification goes away; no leftover "room CODE · Not in a room" notification |
| 8a | Play in Netflix opens the movie | Host plays a Netflix **movie** (e.g. The Paradise); B joins and taps **Play in Netflix** | B's Netflix starts that movie directly (no search), then jumps to the host's position. "Movie found with Watchmode" shows under the button. Movie not in Watchmode (e.g. Extraction): Netflix search opens with the title typed in. Episodes: same as before. Relay logs: `npx wrangler tail` in push-relay/ |
| 16 | No session | Close Netflix on B entirely | B status "Netflix not open on this phone"; countdown card available |
| 17 | Countdown | A taps **3, 2, 1 countdown** | Both phones show Get ready → 3 → 2 → 1 → PLAY NOW within ~0.3 s of each other |
| 18 | Leave | B taps Leave room | A shows B removed; B back on Home |
| 19 | Chat & reactions | Send messages and reactions both ways | Appear on the other phone within ~1 s |
| 20 | Bubble | Grant "Display over other apps", tap **Chat bubble over the movie**, open Netflix | Bubble floats over Netflix, draggable, tap to expand, send works, doesn't block Netflix touches when collapsed |
| 21 | JioHotstar | After filling in `JIOHOTSTAR_PACKAGE`, repeat 1, 2, 4, 8 with JioHotstar | Same as Netflix, or the fallback notice matching its capabilities |
| 22 | Fallback notices | If an app lacks SEEK_TO or doesn't report position | Status card shows the matching notice; play/pause still syncs |

Report for each failed row: phone models + Android versions, what happened, and
`adb logcat -s SyncItDiag SyncItCoordinator SyncItGateway` around the time.

## Jams (Spotify, YouTube Music, Amazon Music, YouTube), added 2026-10-15

Deploy the rules (`firebase deploy --only database`) **before** shipping an APK with jams: older
rules reject jam previews and jam join requests. Logs: `adb logcat -s SyncItDiag SyncItCoordinator`.

| # | Test | Expected |
|---|---|---|
| J1 | Play a song in each app; read `ref=` in SyncItDiag | Spotify `spotify:track:…`; YouTube/YT Music watch links if they publish a video id; Amazon Music none |
| J2 | Friend "Ask to jam" on a Spotify activity, host accepts | Member lands in the room; Spotify starts the host's track at the host's position |
| J3 | Same with the member's Spotify closed | Room screen opens Spotify on the track once; following continues |
| J4 | Host skips / picks another song / song ends naturally (×5) | Member follows within ~3 s; host's song never restarts; no seek bounce |
| J5 | Member skips to another song | "Back to the jam" after ~5 s; room unaffected; button brings them back; not pulled back automatically on the host's next track |
| J6 | YouTube jam | Which play-from actions YouTube advertises; "Back to the jam" opens the video if it has none; ads don't derail members |
| J7 | Spotify Free on the member | Does playFromUri play the exact track or shuffle? |
| J8 | Netflix room (no link), host pauses, plays a YouTube video, returns to Netflix | Members never seek and never switch to a jam |
| J9 | Netflix room, throttle one phone's network mid-movie | Others are not pulled backwards |
| J10 | Scrub in Netflix while it buffers | Everyone follows the scrub |
| J11 | Spotify playing in the background, host taps Start, opens Netflix and plays | Room syncs Netflix (watch party), not Spotify |
| J12 | Watch party with no title, host switches Netflix → Prime Video | Host's Prime doesn't jump or pause; members show "Not on the same title" until they switch, then align |
| J13 | Jam: host presses "next" mid-track | Members never replay the old song from 0 (an app whose metadata lags > 0.8 s may still cause one blip) |

## Chat times + typing indicator (added 2026-10-29)

| # | Test | Expected |
|---|---|---|
| C1 | Send DMs and room-chat messages | Time inside each bubble, bottom-right ("10:42 pm"); follows the phone's 12/24-hour setting |
| C2 | Chat spanning several days | "Today" / "Yesterday" / weekday / date pills between days |
| C3 | Friend types in the DM | Their phone: nothing. Yours: "typing…" under their name + three bouncing dots within ~1 s |
| C4 | Friend sends / clears the box / presses back / home | Indicator disappears at once |
| C5 | Friend force-stops the app or turns on airplane mode mid-typing | Indicator disappears within ~6 s |
| C6 | Long and short messages, light + dark theme | Time sits on the last line when it fits, otherwise on its own line; readable |
