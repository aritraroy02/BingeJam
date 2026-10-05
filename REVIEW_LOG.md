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

## Residual risks (verify on a real machine)
1. Dependency versions in `libs.versions.toml` haven't been verified by a build.
2. Rules `state.seq` validation vs `ServerValue.increment` (see RUN_ON_MY_MACHINE.md).
3. Whether Netflix/JioHotstar honour `transportControls.seekTo` and report positions. That's what Phase 0 diagnostics will tell us.
4. `mediaPlayback` FGS type acceptance on Play (PLAY_STORE_READINESS.md §2).
