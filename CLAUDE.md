# CLAUDE.md: Sync it

Android watch-party app: each person plays the same movie in the official Netflix or JioHotstar
app on their own phone, and Sync it keeps play/pause/position in sync through Android
MediaSession. Only control events are synced, never video or audio.

**Status (2026-10-15):** v2 "Vyro" social restructure built (accounts via Google/email link, unique
usernames, friends, DMs, per-app activity sharing, ask-to-join/invites, Prime Video) by the
backend/media/ui agents and integrated on the real Firebase backend (`USE_FAKE_SOCIAL = false` in
`app/.../di/AppContainer.kt`). `./gradlew assembleDebug lint test` passes (195 tests, 0 lint issues);
rules emulator test 215/215. Not yet: rules deployed, device-tested, released. See PLAN.md "v2".
Working name **Vyro** lives in `core/config/Brand.kt`, `app_name`, web/ (will be renamed again).

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

Firebase project: **`syncit-watchparty`** (owner aritraditya.roy@gmail.com), Realtime Database
`syncit-watchparty-default-rtdb` in asia-southeast1 (Mumbai isn't offered for RTDB), Anonymous
auth on. `DATABASE_URL` in `FirebaseConfig.kt` is set. `firebase.json`/`.firebaserc` point the
CLI at `data-firebase/firebase/database.rules.json`; publish with `firebase deploy --only database`
(the user runs it, or explicitly approves).
| JioHotstar link hosts | `core/.../model/StreamingService.kt` → `listOf("hotstar.com", "jiohotstar.com")` | Unverified guess; only used for a soft warning. Check against a real share link. |
| Invite site | `web/` + `firebase.json` hosting; `RoomLinks.WEB_HOST` | `https://syncit-watchparty.web.app/join/CODE` (`firebase deploy --only hosting`, the user runs deploys). APK = `sync-it.apk` attached to GitHub releases of public repo `aritraroy02/sync-it-releases` (Spark plan can't host APKs). Ship a version with `bash scripts/release.sh X.Y.Z "notes"`. |
| Release signing | `keystore.properties` (git-ignored) → `C:/Users/aritr/.syncit/syncit-release.jks` | Never commit. Its fingerprint is in `web/.well-known/assetlinks.json`. |
| Privacy policy | `web/privacy.html` → https://syncit-watchparty.web.app/privacy | Contact: aritraditya.roy@gmail.com (user-provided 2026-10-15). Keep in sync with app behaviour. |

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
7. **Tests:** 47 engine tests in `sync-engine/src/test`, all passing. If one fails after a
   change, check whether the test's expectation or the engine is wrong before changing either.
8. **Windows build quirk:** `gradlew clean` can fail with "Unable to delete ... lint-cache" while
   a daemon holds the jars. Run `gradlew --stop` first.
