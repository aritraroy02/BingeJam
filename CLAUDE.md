# CLAUDE.md: Sync it

Android watch-party app: each person plays the same movie in the official Netflix or JioHotstar
app on their own phone, and Sync it keeps play/pause/position in sync through Android
MediaSession. Only control events are synced, never video or audio.

**Status (2026-10-28):** v0.3.0 (v2 "Vyro" social restructure: Google/email-link accounts, unique
usernames, friends, DMs, per-app activity sharing, ask-to-join/invites, Prime Video) is released.
Built on `main` since, **not released yet** (next release = 0.4.0):
- Push notifications for new DMs and friend requests: FCM, sent by `push-relay/` (Cloudflare
  Worker, free) because Cloud Functions need Blaze. Tested on the user's OPPO with a test bot.
- Profile pictures (photo or GIF ≤ 1 MB) stored in the same Worker's KV namespace `AVATARS`;
  shown with Coil. Photo upload tested on the phone; GIF not yet confirmed.
- "News & updates" channel so Firebase-console announcements also show while the app is open.
- **Jams** (synced listening) for Spotify, YouTube Music, Amazon Music, YouTube: friend asks to
  jam / is invited, the same track starts on their phone (playFromUri/playFromSearch or the app opens
  on the exact item), play/pause/seek sync, members follow the host's track changes. See PLAN.md
  "Jams" and RUN_ON_MY_MACHINE.md J1–J10 (not yet tried on two phones). Needs the jam rules deployed.
- Already **live** in production: Worker (notify + avatars), RTDB rules (`pushTokens`, `photoUrl`
  prefix), hosting (privacy policy). Old app versions keep working; the new features need 0.4.0 on
  both the sender's and the receiver's phone.
Working name **Vyro** lives in `core/config/Brand.kt`, `app_name`, web/ (will be renamed again).

## Next steps (as of 2026-10-28)
1. **Release 0.4.0 from the dev machine** (it has the release key; never create a new key, since installs
   couldn't update): `git pull`, `gh auth status`, `bash scripts/release.sh 0.4.0 "Notifications for
   messages and friend requests; profile pictures and GIFs"`, then `git push --follow-tags`.
   Users on 0.3.0 then see the "update available" banner on Home (UpdateChecker → GitHub releases).
2. On the user's OPPO: it currently runs a **debug build signed on the work laptop**. Uninstall it
   before installing the release (`adb uninstall com.syncit.app`); the user signs in again.
3. Confirm a GIF profile picture animates (Edit profile → Change).
4. Known open items: web/index.html and web/join.html still say "Sync it" (app and privacy say
   Vyro); Google sign-in on a non-release build failed with "[16] Account reauth failed" while the
   phone's mobile data was unvalidated (works in releases; `SyncItAuth` logs Google's reason now);
   no cleanup job for old rooms/chats; rooms/previews/chats still allow anonymous v1 users.
5. The work laptop's debug signing fingerprints (SHA-1 `CB:AC:27:…`, SHA-256 `C1:6B:F2:…`) were added
   to the Firebase Android app for testing; remove them in Project settings if no longer needed.

Read these before changing anything:
- `PLAN.md`: architecture, module ownership, sync rules, Firebase data model.
- `REVIEW_LOG.md`: what was checked per phase, fixes made, residual risks.
- `RUN_ON_MY_MACHINE.md`: build/test/install steps and the two-phone manual checklist.
- `PLAY_STORE_READINESS.md`: permission disclosures and privacy-policy points.

## Hard rules (from the original spec)

- MediaSession only: `NotificationListenerService` + `MediaSessionManager.getActiveSessions` +
  `MediaController.transportControls`. **No** AccessibilityService, **no** screen/audio capture,
  **no** private Netflix/JioHotstar APIs, **never** read notification content.
- Timing: local time = `SystemClock.elapsedRealtime()`; room time = server time
  (`ServerValue.TIMESTAMP` + `ServerClock`). Never `System.currentTimeMillis()` for sync decisions
  (one documented exception: `FirebaseServerClock` anchors Firebase's offset). Comment timing math.
- Module boundaries: `:core` has contracts only (no Android, no logic). `:sync-engine`, `:media`
  and `:data-firebase` depend on `:core` only. `:app` is the only module that wires implementations.
- If a streaming app lacks a capability, degrade gracefully through the existing fallback modes
  (`SyncMode`); don't hack around it.
- Ask the user before adding a major dependency or deviating from the spec.
- Verify changes with `./gradlew assembleDebug lint test`. Steps that need a real phone or the
  Firebase console go in `RUN_ON_MY_MACHINE.md`.
- **Production repo:** never commit `google-services.json`, keystores or other secrets. Don't
  deploy Firebase rules or touch the production Firebase project without the user's go-ahead.
- Commits: short message (subject + a line or two), no `Co-Authored-By` trailer.

## Modules

| Module | Key files |
|---|---|
| `:core` | `config/StreamingConfig.kt`, `model/*`, `media/MediaControllerGateway.kt`, `transport/SyncTransport.kt`, `sync/SyncEngine.kt`, `session/SyncSession.kt` |
| `:sync-engine` | `DefaultSyncEngine.kt` (all sync logic), `PositionMath.kt`, `EchoSuppressor.kt`, `CapabilityDetector.kt`, `SyncConfig.kt` (all thresholds); tests in `src/test` with `FakeClock` |
| `:media` | `AndroidMediaControllerGateway.kt`, `MediaListenerService.kt`, `service/RoomSyncCoordinator.kt` (glues gateway + transport + engine), `service/SyncForegroundService.kt` |
| `:data-firebase` | `FirebaseSyncTransport.kt`, `FirebaseServerClock.kt`, `RoomParser.kt`, `firebase/database.rules.json` |
| `:app` | `di/AppContainer.kt` (manual DI), `ui/nav/Navigation.kt` (simple back stack), screens under `ui/`, `overlay/ChatBubbleService.kt` |

When the user reports an error, fix it in the owning module. If the fix changes a `:core`
contract, update every implementation and the tests.

## Left empty / placeholders (must be filled in)

| What | Where | How to fill |
|---|---|---|
| **`google-services.json`** | `app/google-services.json` (git-ignored; see `app/google-services.json.README.md`) | Present on the main dev machine (project `syncit-watchparty`). Elsewhere: `firebase apps:sdkconfig ANDROID 1:289263837364:android:627d7a1647f21ad28d77e1 -o app/google-services.json`. The build fails without it. |
| Push relay | `push-relay/` deployed 2026-10-28 at `https://vyro-push-relay.aritraroy02.workers.dev` (Cloudflare account of the user, subdomain `aritraroy02`); URL in `FirebaseConfig.PUSH_RELAY_URL` | Redeploy with `npx wrangler deploy` in `push-relay/`. Its `FIREBASE_SERVICE_ACCOUNT` secret lives only in Cloudflare: never commit the key. Logs: `npx wrangler tail`. Also serves profile pictures (`PUT/DELETE /avatar`, `GET /avatar/<uid>`) from KV namespace `AVATARS` (id in `wrangler.toml`); Firebase Storage needs Blaze, so it isn't used. |
| JioHotstar link hosts | `core/.../model/StreamingService.kt` → `listOf("hotstar.com", "jiohotstar.com")` | Unverified guess; only used for a soft warning. Check against a real share link. |
| Invite site | `web/` + `firebase.json` hosting; `RoomLinks.WEB_HOST` | `https://syncit-watchparty.web.app/join/CODE` (`firebase deploy --only hosting`, the user runs deploys). APK = `sync-it.apk` attached to GitHub releases of public repo `aritraroy02/sync-it-releases` (Spark plan can't host APKs). Ship a version with `bash scripts/release.sh X.Y.Z "notes"`. |
| Release signing | `keystore.properties` (git-ignored) → `C:/Users/aritr/.syncit/syncit-release.jks` | Never commit. Its fingerprint is in `web/.well-known/assetlinks.json`. |
| Privacy policy | `web/privacy.html` → https://syncit-watchparty.web.app/privacy | Contact: aritraditya.roy@gmail.com (user-provided 2026-10-15). Keep in sync with app behaviour. |

Firebase project: **`syncit-watchparty`** (owner aritraditya.roy@gmail.com), Realtime Database
`syncit-watchparty-default-rtdb` in asia-southeast1 (Mumbai isn't offered for RTDB), Anonymous
auth on. `DATABASE_URL` in `FirebaseConfig.kt` is set. `firebase.json`/`.firebaserc` point the
CLI at `data-firebase/firebase/database.rules.json`; publish with `firebase deploy --only database`
(the user runs it, or explicitly approves). Spark (free) plan: no Cloud Functions, so push
notifications go through `push-relay/` (Cloudflare Worker) instead.

## UI
Liquid glass (user's choice): `ui/theme/Theme.kt` (palettes, Inter font), `ui/glass/Glass.kt`
(GlassScreen backdrop + Haze blur, `Modifier.glass`, buttons, fields, segmented control). Haze is
pinned to 1.6.x: 2.x needs AGP 9 + compileSdk 37. Theme mode (System/Light/Dark) is in Settings.
Don't use `FontFamily.Monospace`: OPPO maps it to a serif font; use `Inter`.

## Unverified / likely to need changes

1. **Dependency versions** in `gradle/libs.versions.toml` resolve and build (AGP 8.11.1, Kotlin 2.2.0,
   Gradle 8.13, JDK 21). They're dated mid-2025; bumping is optional.
2. **Firebase rule for `state.seq`**: verified in the emulator. `ServerValue.increment(1)` passes,
   a stale literal seq is rejected. No change needed.
3. **Real app behaviour is unknown:** whether Netflix/JioHotstar expose a session, report
   positions, and honour `seekTo`. The engine picks a fallback mode at runtime, but thresholds in
   `SyncConfig.kt` may need tuning after the two-phone tests.
4. **Foreground service type `mediaPlayback`** may be questioned by Play; `specialUse` is the fallback.
5. **No data cleanup:** rooms, chats and reactions are never deleted. Needs a scheduled cleanup
   before launch.
6. **Known small gaps:**
   - If the system restarts `SyncForegroundService` (`START_REDELIVER_INTENT`) without the UI,
     sync resumes but presence isn't restarted until the room screen is opened.
   - A host leaving doesn't hand over host or delete the room.
   - `RoomViewModel` is reused if the same room is left and re-entered; `enter()` handles that.
7. **Tests:** 83 engine tests in `sync-engine/src/test`, all passing. If one fails after a
   change, check whether the test's expectation or the engine is wrong before changing either.
8. **Windows build quirk:** `gradlew clean` can fail with "Unable to delete ... lint-cache" while
   a daemon holds the jars. Run `gradlew --stop` first.
