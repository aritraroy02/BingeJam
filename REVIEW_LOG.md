# Review log

**Caveat:** the initial build was a single session. The Orchestrator did each role's work
itself, keeping to module ownership, and ran the review gate as a static self-review
(reading + grep). That's weaker than an independent pass. Re-run `reviewer-agent` (defined in
`.claude/agents/`) for a second opinion; it can now also run the build and tests.

Nothing has been compiled or run. "PASS" below means "passes static review".

## Checks applied every phase
- Hard constraints: grep for `AccessibilityService`, `MediaProjection`, `AudioRecord`,
  notification-content access, a hardcoded JioHotstar package → none found.
- Timing: `System.currentTimeMillis` appears once, in `FirebaseServerClock`, to anchor
  Firebase's `.info/serverTimeOffset` (which is defined against the wall clock); all other
  timing is `elapsedRealtime` or server time, with comments.
- Module boundaries: `:media`, `:data-firebase`, `:sync-engine` import only `:core`;
  `:app` is the DI/wiring layer and is the only module importing implementations.

## Phase 0: diagnostics. PASS
- media-agent: `MediaListenerService`, `AndroidMediaControllerGateway`, `PlaybackStateMapper`, `DiagnosticsLog`.
- ui-agent: `DiagnosticsScreen`/`ViewModel`, `Permissions`, app shell.
- Note: `:media` manifest declares `SyncForegroundService` ahead of Phase 2 (resolved in Phase 2).

## Phase 1: onboarding + rooms. PASS (1 fix)
- backend-agent: `FirebaseSyncTransport` (auth, rooms, presence/onDisconnect), `FirebaseServerClock`, `database.rules.json`.
- ui-agent: onboarding, Home, room frame, `MembersCard`.
- Fixed: presence wasn't restarted after process recreation → `RoomViewModel.enter()` re-joins (idempotent).
- Fixed: `isPermissionDenied()` compared against an int constant; simplified to the message check.
- Deviation (noted): battery step opens the optimization *list*; the direct dialog needs a Play-restricted permission.

## Phase 2: play/pause sync. PASS (2 fixes)
- sync-engine-agent: `DefaultSyncEngine`, `EchoSuppressor`, `CapabilityDetector`, `SyncConfig`, `PlayPauseSyncTest`.
- media-agent: `RoomSyncCoordinator`, `SyncForegroundService` (mediaPlayback), `AndroidSyncSessionController`.
- Fixed: callable reference to a companion function (`StreamingService::forPackage`) won't compile → lambda.
- Fixed: coordinator helper named `apply` shadowed the stdlib scope function → renamed `execute`.
- Design note: `STOPPED` is not treated as a pause, so one person closing the player doesn't pause the room.

## Phase 3: timeline sync. PASS
- sync-engine-agent: `PositionMathTest`, `TimelineSyncTest`, `FallbackModesTest` (extrapolation, seek detection, drift threshold, every-5-s check, rate limit, give-up after 3 failed seeks, late join, reopen, fallbacks).
- backend-agent: `reportPosition` (members/{uid}.positionMs + reportedAt).
- ui-agent: drift overlay in `MembersCard`.
- Each test's expected values were traced by hand through the engine; not executed.

## Phase 4: title. PASS
- ui-agent: `TitleCard` (service chips, link check, host-only editor, Open movie via ACTION_VIEW official app first, same-title confirmation dialog).
- backend-agent: `setTitle`; rules restrict `title` to the host.

## Phase 5: polish. PASS (1 fix)
- ui-agent: `CountdownCard`, `ChatSection`, `SyncStatusCard`, `ChatBubbleService` (SYSTEM_ALERT_WINDOW).
- backend-agent: chats/reactions trees + rules.
- Fixed: `FlowRow` is experimental in some Compose versions (opt-in compile error) → replaced with plain text.

## Build verification (2026-10-05)
First real build on Windows 11: JDK 21 (Temurin), Android SDK platform 36, Gradle 8.13 wrapper
(now committed). Placeholder `google-services.json` for compiling only.
- `./gradlew assembleDebug lint test`: **BUILD SUCCESSFUL**, 0 lint issues in every module,
  47/47 sync-engine tests pass (PlayPause 12, Timeline 16, Fallback 9, PositionMath 10).
- Fixed: `RoomParser.chat`/`reaction` used `return null` inside an expression body (compile
  error) → block bodies.
- Fixed: redundant null check in `CountdownCard` (compiler warning).
- Fixed lint: `mipmap-anydpi-v26` → `mipmap-anydpi` + monochrome icon; backup-exclusion rules
  (`data_extraction_rules.xml`, `backup_rules.xml`) to match `allowBackup="false"`;
  `toUri()` / `SharedPreferences.edit {}` KTX.
- Removed `core/bin/` and `sync-engine/bin/` (VS Code Java output committed in bcb8407,
  identical to `src/`); `bin/` is now git-ignored.
- **Rules emulator replay:** every `FirebaseSyncTransport` write shape run against
  `database.rules.json` in the Firebase Local Emulator (offline `demo-` project), 28/28 as
  expected: create room / taken code / bad code, join / join missing room, member vs outsider
  read, state writes with `ServerValue.increment` (seq 1 → 2), forged `updatedBy` and stale seq
  rejected, position report, host-only title, countdown, chat/reaction write + indexed read,
  outsider chat denied, `hostUid` immutable, onDisconnect presence, leave, host deletes room.
- Merged manifest permissions: INTERNET, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW,
  FOREGROUND_SERVICE(+_MEDIA_PLAYBACK), plus Firebase's ACCESS_NETWORK_STATE / READ_GSERVICES.

## First on-device run (2026-10-06, OPPO CPH2607, Android 16)
- Install + launch: no crashes. Onboarding: Notification Access, notifications, battery
  exemption all detected (✓); name saved.
- Diagnostics screen lists sessions live; actions decode matches `dumpsys media_session`.
- **Netflix** (`com.netflix.mediaclient`): actions `0x36b` playing / `0x36d` paused (PAUSE↔PLAY
  swap; PLAY_PAUSE, SEEK_TO always). Position reported and frozen while paused. Keeps playing
  in PiP when Sync it is opened. Diagnostics **Pause, Play, Seek +30s all took effect** →
  expect `FULL` mode.
- **JioHotstar** = `in.startv.hotstar` (v26.09.14.1), read from the live session and set in
  `StreamingConfig`. Actions include PAUSE, PLAY_PAUSE, SEEK_TO, SET_SPEED; position reported.
  Responds to media-key play/pause (`cmd media_session dispatch`). Goes to state NONE when
  backgrounded (no PiP), so the diagnostics buttons can't reach it while Sync it is in front;
  **transportControls play/pause/seek on JioHotstar still to verify in a real room**. NONE
  maps to no room status, so going idle doesn't pause the room.
- Watch in the room test: JioHotstar reports PAUSED when it's brought back to the front, and
  probably also when the user leaves it to open Sync it. That would broadcast a pause to everyone.
  The chat bubble avoids leaving the app; consider ignoring a pause right before the app goes to NONE.
- Rooms not tested yet: rules not deployed (auto-mode blocks `firebase deploy`).

## Glass UI, now-playing preview, invite links (2026-10-07)
- Build: `assembleDebug lint test` green, 0 lint issues, 56 tests (47 engine + 9 `RoomLinksTest`).
  Release build: R8 + resource shrinking, 2.7 MB, signed with the release key.
- Rules: emulator replay 39/39 (11 new for `previews`: host-only write, any signed-in read by
  code, no listing, size/field limits, null clears artwork). Deployed to production.
- On the OPPO (Android 16): glass Home / Settings / Join preview / Room render correctly in dark
  and light; the theme switch is instant and status-bar icons follow it. Creating a room wrote
  `previews/SRWBAA {hostName}` live. The clipboard handoff picked up an old invite on first launch.
- Invite site live at https://syncit-watchparty.web.app (`/join/CODE`, assetlinks.json). APK served
  from `github.com/aritraroy02/sync-it-releases/raw/main/sync-it.apk` (Firebase Spark rejects
  executables); the download is byte-identical to the signed build.
- App Links: `pm get-app-links` → `syncit-watchparty.web.app: verified`; opening
  `https://syncit-watchparty.web.app/join/SRWBAA` went straight to the app's Join preview.
- Fixed on device: OPPO maps `FontFamily.Monospace` to a serif font → codes use Inter.
- Not yet verified: what title/artwork Netflix and JioHotstar publish (needs a title playing);
  the room hero with real artwork; the full fresh-install path (download → install → auto-join)
  on a second phone; the chat bubble overlay still has the old look.

## Release 0.2.1 on the phone + first real sync test (2026-10-07, battery 2%)
- Netflix MediaSession metadata: title = episode name ("Redanian Intelligence"), subtitle
  "S2:E4 - Redanian Intelligence" (repeats the title), artwork = 343×480 portrait poster
  (26 KB thumbnail, 35,844 base64 chars in `previews/8Z7ZPZ`). The room hero showed the poster,
  NETFLIX badge and episode title, read back from Firebase. The phone seeded the room state
  (paused at 2.984 s, matching Netflix).
- Bot test in room 8Z7ZPZ (Netflix in front): room seek+play → Netflix jumped to 63 s and played ✅;
  pause pressed on the phone → room paused at 72.5 s ✅; **resume after pause (from the room, or a media
  key on the phone) → Netflix played 0.2–0.3 s then paused itself**, and that pause was broadcast.
  Audio focus history shows no other app took focus; not our engine (the pause was broadcast as a
  user action, so not an echo of our own command). Same resume worked on 2026-10-06 at 13% battery →
  suspect OPPO extreme power saving at 2%. **Retest on a charged phone.** If it persists, consider not
  broadcasting a pause that arrives within ~1 s of a resume and re-aligning to the room instead.
- Small UI follow-up: show the subtitle as just "S2:E4" when it repeats the title.

## APK download stuck "in progress" (2026-10-07)
- Reproduced on the OPPO (Chrome 154, battery 4%): Chrome Downloads shows "sync-it.apk 2.73 MB/2.73 MB"
  with a pause icon; on disk it's `/sdcard/Download/.pending-…-sync-it.apk` (all bytes there, never
  finalized). No "Download anyway" prompt visible. GitHub serves it correctly (200, 2,733,125 B,
  `application/vnd.android.package-archive`, `Content-Disposition: attachment`), so not a hosting problem.
- Likely causes: Chrome's APK safety check hanging, or OPPO battery saver freezing Chrome's download
  service. Not yet confirmed on another phone.
- Invite site now: detects in-app browsers (Instagram/Facebook/Snapchat WebViews) → "Open in Chrome";
  after Download shows install steps + the stuck-at-2.7 MB fix (tap it in Downloads, remove, turn off
  battery saver, retry, or use another browser) + "Download again"; self-hosted Inter (no serif fallback).
- If friends still get stuck: options not chosen yet are sending the APK in chat from the app, Firebase
  App Distribution, or Play internal testing.

## Residual risks (verify on a real machine)
1. ~~Dependency versions~~: resolved, they build.
2. ~~Rules `state.seq` vs `ServerValue.increment`~~: resolved, verified in the emulator.
3. Whether Netflix/JioHotstar honour `transportControls.seekTo` and report positions. That's what Phase 0 diagnostics will tell us.
4. `mediaPlayback` FGS type acceptance on Play (PLAY_STORE_READINESS.md §2).
5. Nothing has run on a device or against a real Firebase project yet.
