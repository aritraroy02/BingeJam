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
  the public repo `github.com/aritraroy02/sync-it-releases`; the site links to
  `.../releases/latest/download/sync-it.apk`, so the file name must stay `sync-it.apk`.
- New app version:
  ```bash
  bash scripts/build-release-apk.sh         # signed release APK → dist/sync-it.apk
  # then: GitHub → sync-it-releases → Releases → new release, attach dist/sync-it.apk
  ```
- Rules or site changes: `firebase deploy --only database,hosting`
- Check App Links on a phone: `adb shell pm verify-app-links --re-verify com.syncit.app`, then
  `adb shell pm get-app-links com.syncit.app` should show `syncit-watchparty.web.app: verified`.

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
| 10 | Kill & reopen Netflix | On B: swipe Netflix away from recents | B status: "Session not found". Reopen the title on B → it seeks to the room position and matches play/pause; A unaffected |
| 11 | Netflix autoplay | B reopens Netflix and it autoplays from its resume point | B is pulled to the room, A is NOT moved |
| 12 | Network loss 30 s | Airplane mode on B for 30 s while playing; A pauses meanwhile | A shows B offline. When B reconnects, B pauses to match A |
| 13 | Network loss, local action | Airplane on B, B pauses, wait 30 s, airplane off | After reconnect the room follows the last write; both end in the same state |
| 14 | Screen off | B turns screen off for 2 min while A keeps playing, then on | B still in sync (or corrects within ~5 s); sync notification still present |
| 15 | Stop from notification | B taps **Stop syncing** in the notification | B stops following; status "Not in a room" + **Resume syncing** works |
| 16 | No session | Close Netflix on B entirely | B status "Netflix not open"; countdown card available |
| 17 | Countdown | A taps **3, 2, 1 countdown** | Both phones show Get ready → 3 → 2 → 1 → PLAY NOW within ~0.3 s of each other |
| 18 | Leave | B taps Leave room | A shows B removed; B back on Home |
| 19 | Chat & reactions | Send messages and reactions both ways | Appear on the other phone within ~1 s |
| 20 | Bubble | Grant "Display over other apps", tap **Chat bubble over the movie**, open Netflix | Bubble floats over Netflix, draggable, tap to expand, send works, doesn't block Netflix touches when collapsed |
| 21 | JioHotstar | After filling in `JIOHOTSTAR_PACKAGE`, repeat 1, 2, 4, 8 with JioHotstar | Same as Netflix, or the fallback notice matching its capabilities |
| 22 | Fallback notices | If an app lacks SEEK_TO or doesn't report position | Status card shows the matching notice; play/pause still syncs |

Report for each failed row: phone models + Android versions, what happened, and
`adb logcat -s SyncItDiag SyncItCoordinator SyncItGateway` around the time.
