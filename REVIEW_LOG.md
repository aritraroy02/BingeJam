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

## Residual risks (verify on a real machine)
1. ~~Dependency versions~~: resolved, they build.
2. ~~Rules `state.seq` vs `ServerValue.increment`~~: resolved, verified in the emulator.
3. Whether Netflix/JioHotstar honour `transportControls.seekTo` and report positions. That's what Phase 0 diagnostics will tell us.
4. `mediaPlayback` FGS type acceptance on Play (PLAY_STORE_READINESS.md §2).
5. Nothing has run on a device or against a real Firebase project yet.
