# CLAUDE.md: Sync it

Android watch-party app: each person plays the same movie in the official Netflix or JioHotstar
app on their own phone, and Sync it keeps play/pause/position in sync through Android
MediaSession. Only control events are synced, never video or audio.

**Status (2026-10-29):** **v0.6.1 released** (versionCode 11, released from the Asus laptop as gh
`somesh118`; rules + hosting deployed first). New in 0.6.1:
- **Play in Netflix opens the host's exact movie.** Netflix's MediaSession has no ID (5 metadata keys,
  no queue, checked on Netflix 9.89), so members' `RoomViewModel.netflixId` asks the relay's `GET /netflix`
  (title + length -> Watchmode catalogue, region IN) and opens `netflix.com/watch/<id>`, which starts
  playing; the engine then jumps to the room. Not found -> `netflix.com/search/<title>` (Netflix fills the
  search box). Episodes are skipped (Netflix gives only the episode name). See PLAN.md "Netflix exact title".
- **Pop-up notifications for "Ask to join/jam" and room invites** (relay types `join_request`/`invite`,
  channel `requests` = IMPORTANCE_HIGH); a tap opens Home, where the request banner is.
- **New-version notifications:** relay cron (every 30 min) checks the releases repo and sends one data-only
  FCM message to topic `updates`; the app shows it only if newer than the installed version. Installs
  subscribe from 0.6.1 on, so the first one anyone sees is for 0.6.2.
- **"In a watch party with Somesh"**: presence `activity/watchingWith` (online room members' names).
- **Sync fixes found on a Motorola Edge 60 Pro (Android 17):** Netflix's first reading after opening a title
  is CONNECTING with position -1, which turned timeline sync off for that attach (now -1 only counts while
  PLAYING/PAUSED); Netflix lands seeks on the keyframe up to 5 s early and autoplays after a seek, which made
  late-join realign ~25 times in 4 s (now at most `maxSettleRealigns` = 2, then once after the window);
  Netflix reports PLAYING ~1 s before the picture moves, so seeks into a playing room aim
  `NETFLIX_SEEK_LEAD_MS` = 1 s ahead (`RoomSyncCoordinator`). Status text: "Netflix closed on this phone"
  instead of "Session not found".
- 528 unit tests (core 16, sync-engine 86, media 188, data-firebase 76, app 162), lint clean.

Earlier: **v0.6.0**: WhatsApp-style message times + day pills (DM and room chat) and a DM typing indicator
(`typing/{dmId}/{uid}`, rules 439/439); checklist C1-C6. **v0.5.1**: "Quit BingeJam" action in the sync
notification (stops sync, bubble, presence/activity and unbinds the notification listener until the app
is opened again) and no more leftover "Not in a room" notification. **v0.5.0**: renamed **BingeJam**,
in-app account deletion, Terms/Privacy/delete-account pages (DPDP Act + IT Rules 2021 grievance officer, 18+).
v0.3.0 was the v2 "Vyro" social restructure (Google/email-link accounts, unique usernames, friends,
DMs, per-app activity sharing, ask-to-join/invites, Prime Video). New in 0.4.0:
- Push notifications for new DMs and friend requests: FCM, sent by `push-relay/` (Cloudflare
  Worker, free) because Cloud Functions need Blaze. Tested on the user's OPPO with a test bot.
- Profile pictures (photo or GIF ≤ 1 MB) stored in the same Worker's KV namespace `AVATARS`;
  shown with Coil. Photo upload tested on the phone; GIF not yet confirmed.
- "News & updates" channel so Firebase-console announcements also show while the app is open.
- **Jams** (synced listening) for Spotify, YouTube Music, Amazon Music, YouTube: friend asks to
  jam / is invited, the same track starts on their phone (playFromUri/playFromSearch, or the room
  screen opens the app on the exact item), play/pause/seek sync, members follow the host's track
  changes. See PLAN.md "Jams". **Not yet tried on two phones** (RUN_ON_MY_MACHINE.md J1–J13).
- Sync engine: scrubs through BUFFERING are broadcast (were dropped), stalls aren't seeks; host
  switching app/episode is a leader item change; room kind (watch party vs jam) locks once someone joins.
- Live in production: Worker (notify + avatars), RTDB rules (pushTokens, photoUrl, jam services,
  playableRef; deployed 2026-10-28, emulator 332/332), hosting (privacy policy incl. jams).
  `./gradlew assembleDebug lint test`: 433 tests, 0 failures, lint clean.
Name: **BingeJam** (user's choice 2026-10-29; was Vyro, before that Sync it) in `core/config/Brand.kt`,
`app_name`, web/. Package stays `com.syncit.app`; release asset stays `sync-it.apk`. Legal pages: web/privacy,
web/terms, web/delete-account; checklist + trademark filing pack: `legal/LEGAL_CHECKLIST.md`.

## Next steps (as of 2026-10-29)
1. Two-phone check of 0.6.1 (RUN_ON_MY_MACHINE.md 8a, 15a/15b): host vs member offset with the 1 s
   Netflix lead (tune `NETFLIX_SEEK_LEAD_MS`), join-request pop-up on the host's phone, "watching with" on a
   friend's Home. Member-side logs: `adb logcat -G 16M`, then `adb logcat -s SyncItDiag SyncItLookup`.
2. Two-phone jam test (RUN_ON_MY_MACHINE.md J1-J13): what Spotify/YouTube/YT Music/Amazon Music
   publish as MEDIA_ID (`ref=` in SyncItDiag), whether they honour playFromUri/playFromSearch, track
   changes, and the watch-party regressions J8-J12. Thresholds may need tuning.
3. **Watchmode:** the user should rotate the API key (it was pasted in chat): new key ->
   `npx wrangler secret put WATCHMODE_API_KEY` in `push-relay/`. The free plan is non-commercial with
   attribution (the room shows "Movie found with Watchmode"), 2,500 credits/month, up to 3 countries (IN
   enabled); commercial use needs a paid plan ($349/month) or another source. Usage: `GET /v1/status/`.
4. Test phones: OPPO CPH2607 (Wi-Fi debugging) and Motorola Edge 60 Pro ZA22382JM2 (USB on the Asus laptop;
   has Netflix, JioHotstar, YouTube, YT Music; runs the 0.6.1 release). Friends need a store listing
   (Indus): Play Protect blocks sideloaded apps with Notification Access in India.
5. Confirm a GIF profile picture animates (Edit profile -> Change).
6. Known open items: Google sign-in on a non-release build failed with "[16] Account reauth failed" while the
   phone's mobile data was unvalidated (works in releases; `SyncItAuth` logs Google's reason now);
   no cleanup job for old rooms/chats; rooms/previews/chats still allow anonymous v1 users;
   if the host is in a Netflix room and accepts a Spotify jam request, the friend joins that room;
   the relay's `/netflix` has no per-user rate limit (only the KV cache bounds Watchmode credits).
7. The work laptop's debug signing fingerprints (SHA-1 `CB:AC:27:...`, SHA-256 `C1:6B:F2:...`) were added
   to the Firebase Android app for testing; remove them in Project settings if no longer needed. The Asus
   laptop's debug key (SHA-1 `7E:ED:35:BA:...:6D:6C`) is **not** registered, so Google sign-in fails in its
   debug builds (email link works).
8. Legal: the user files the BingeJam trademark and runs the official IP India search
   (`legal/LEGAL_CHECKLIST.md`), and creates the Indus developer account (PAN).
9. Releases: only from a machine with the release key (never create a new key): the main machine and the
   Asus laptop. Deploy rules/hosting/relay **before** a release that needs them. Then
   `bash scripts/release.sh X.Y.Z "notes"` (GH=path to gh if not on PATH; on the Asus laptop
   `GH=/c/Users/Asus/AppData/Local/Microsoft/WinGet/Links/gh.exe`), then `git push --follow-tags`.

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
| `:media` | `AndroidMediaControllerGateway.kt`, `MediaListenerService.kt` (also unbind/rebind for Quit), `service/RoomSyncCoordinator.kt` (glues gateway + transport + engine; Netflix seek lead), `service/SyncForegroundService.kt` (sync notification: Stop syncing / Quit), `activity/` (friends' activity) |
| `:data-firebase` | `FirebaseSyncTransport.kt`, `FirebaseServerClock.kt`, `RoomParser.kt`, `firebase/database.rules.json`, `social/PushRelay.kt` + `social/RelayTitleLookup.kt` (relay clients) |
| `:app` | `di/AppContainer.kt` (manual DI), `di/SocialSession.kt` (presence/activity, Quit flag), `ui/nav/Navigation.kt` (simple back stack), screens under `ui/` (`ui/room/TitleLinks.kt`: Netflix links), `push/` (FCM), `overlay/ChatBubbleService.kt` |

When the user reports an error, fix it in the owning module. If the fix changes a `:core`
contract, update every implementation and the tests.

## Left empty / placeholders (must be filled in)

| What | Where | How to fill |
|---|---|---|
| **`google-services.json`** | `app/google-services.json` (git-ignored; see `app/google-services.json.README.md`) | Present on the main dev machine and the Asus laptop (project `syncit-watchparty`). Elsewhere: `firebase apps:sdkconfig ANDROID 1:289263837364:android:627d7a1647f21ad28d77e1 -o app/google-services.json`, or rebuild it from a release APK's string resources (`aapt2 dump resources`). The build fails without it. |
| Push relay | `push-relay/` at `https://vyro-push-relay.aritraroy02.workers.dev` (Cloudflare account aritraditya.roy@gmail.com, subdomain `aritraroy02`); URL in `FirebaseConfig.PUSH_RELAY_URL` | Redeploy with `npx wrangler deploy` in `push-relay/` (`npx wrangler login` first on a new machine). Routes: `POST /` notifications (`dm`, `friend_request`, `join_request`, `invite`); `PUT/DELETE /avatar`, `GET /avatar/<uid>` profile pictures in KV `AVATARS` (Firebase Storage needs Blaze); `GET /netflix?title=&durationMs=` Netflix ID lookup via Watchmode (signed-in only; cached in KV `nflx:<title>/<minutes>`, 25 days found, 3 days not found); cron `*/30 * * * *` announces new GitHub releases on FCM topic `updates` (KV `release:announced`). Secrets only in Cloudflare, never in git: `FIREBASE_SERVICE_ACCOUNT`, `WATCHMODE_API_KEY`. Logs: `npx wrangler tail`. |
| JioHotstar link hosts | `core/.../model/StreamingService.kt` → `listOf("hotstar.com", "jiohotstar.com")` | Unverified guess; only used for a soft warning. Check against a real share link. |
| Invite site | `web/` + `firebase.json` hosting; `RoomLinks.WEB_HOST` | `https://syncit-watchparty.web.app/join/CODE` (`firebase deploy --only hosting`, the user runs deploys). APK = `sync-it.apk` attached to GitHub releases of public repo `aritraroy02/sync-it-releases` (Spark plan can't host APKs). Ship a version with `bash scripts/release.sh X.Y.Z "notes"`. |
| Release signing | `keystore.properties` (git-ignored) → `C:/Users/aritr/.syncit/syncit-release.jks` (main machine), `C:/Users/Asus/.syncit/syncit-release.jks` (Asus laptop) | Never commit or send in chat. SHA-256 `E8:89:BE:...:33:5F`, also in `web/.well-known/assetlinks.json`. |
| Privacy policy | `web/privacy.html` → https://syncit-watchparty.web.app/privacy | Contact: aritraditya.roy@gmail.com (user-provided 2026-10-15). Keep in sync with app behaviour. |

Firebase project: **`syncit-watchparty`** (owner aritraditya.roy@gmail.com), Realtime Database
`syncit-watchparty-default-rtdb` in asia-southeast1 (Mumbai isn't offered for RTDB), Anonymous
auth on. `DATABASE_URL` in `FirebaseConfig.kt` is set. `firebase.json`/`.firebaserc` point the
CLI at `data-firebase/firebase/database.rules.json`; publish with `firebase deploy --only database`
(the user runs it, or explicitly approves; `npx firebase-tools@14 login` as the owner first on a new machine). Spark (free) plan: no Cloud Functions, so push
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
3. **Real app behaviour:** Netflix is measured (PLAN.md "Netflix behaviour"): FULL mode works, but seeks
   land on keyframes up to 5 s early and the picture starts ~1 s after PLAYING is reported. JioHotstar,
   Prime Video and the jam apps are still mostly unmeasured; `SyncConfig.kt` thresholds and
   `NETFLIX_SEEK_LEAD_MS` may need tuning after two-phone tests.
4. **Foreground service type `mediaPlayback`** may be questioned by Play; `specialUse` is the fallback.
5. **No data cleanup:** rooms, chats and reactions are never deleted. Needs a scheduled cleanup
   before launch.
6. **Known small gaps:**
   - If the system restarts `SyncForegroundService` (`START_REDELIVER_INTENT`) without the UI,
     sync resumes but presence isn't restarted until the room screen is opened.
   - A host leaving doesn't hand over host or delete the room.
   - `RoomViewModel` is reused if the same room is left and re-entered; `enter()` handles that.
7. **Tests:** 528 unit tests (86 in `sync-engine/src/test`), all passing. If one fails after a
   change, check whether the test's expectation or the engine is wrong before changing either.
   Device behaviour is pinned in tests replaying real logs (`FallbackModesTest`
   `positionUnknownWhileConnecting...`, `TimelineSyncTest` `lateJoin_appThatLandsEarlyAndAutoplays...`).
8. **Windows build quirk:** `gradlew clean` can fail with "Unable to delete ... lint-cache" while
   a daemon holds the jars. Run `gradlew --stop` first.
