# Sync it: plan

Android watch-party app. Each person plays Netflix or JioHotstar in the official app on their
own phone; Sync it runs in the background and keeps play/pause/position in sync over
MediaSession. No video or audio is ever captured or relayed, and only control events are synced.

## Modules

| Module | Owner | Kind | Contents |
|---|---|---|---|
| `:core` | Orchestrator | Kotlin/JVM | Contracts, models and constants only. No Android. |
| `:sync-engine` | sync-engine-agent | Kotlin/JVM | `DefaultSyncEngine`, `PositionMath`, echo suppression, capability detection, unit tests. |
| `:media` | media-agent | Android lib | `MediaListenerService`, `AndroidMediaControllerGateway`, `SyncForegroundService`, `RoomSyncCoordinator`. |
| `:data-firebase` | backend-agent | Android lib | `FirebaseSyncTransport`, `FirebaseServerClock`, security rules. |
| `:app` | ui-agent | Android app | Compose screens, ViewModels, manual DI (`AppContainer`), chat bubble overlay. |

Dependency direction: `:app` → everything; every other module → `:core` only.

## Contracts (`core/src/main/kotlin/com/syncit/core`)

- `media/MediaControllerGateway`: notification-access state, live `sessions`, `play/pause/seekTo(sessionKey)`.
- `transport/SyncTransport`: auth, create/join/leave room, `observeRoom`, `writeState`,
  `reportPosition`, title, countdown, chat, reactions, `serverClock`.
- `sync/SyncEngine` + `SyncEngineFactory` + `SyncClock` (elapsedRealtime + server time).
- `session/SyncSessionController`: start/stop the foreground sync session; `SyncSessionState` for UI.
- Models: `PlaybackSnapshot`, `MediaSessionInfo`, `RoomState`, `Member`, `Room`, `RoomTitle`,
  `Countdown`, `SyncCommand`, `StateBroadcast`, `SyncMode`, `SyncStatus`, `StreamingService`.
- Config: `StreamingConfig` (Netflix package; **JioHotstar package is a blank TODO**),
  `RoomLinks`, `SyncStatusLabels`.

## Runtime flow (in a room)

```
MediaController callbacks ──► AndroidMediaControllerGateway.sessions
                                         │ (target app's snapshot)
Firebase rooms/{code}  ──► observeRoom ──┤
                                         ▼
                 RoomSyncCoordinator (in SyncForegroundService, main thread)
                                         │  onLocalSnapshot / onRoom / onTick (1 s)
                                         ▼
                                  DefaultSyncEngine
                     commands ─► gateway.play/pause/seekTo
                     broadcast ─► transport.writeState
                     positionReport ─► transport.reportPosition
```

## Timing rules

- Local time: `SystemClock.elapsedRealtime()` only (the same base as
  `PlaybackState.getLastPositionUpdateTime()`).
- Room time: `ServerValue.TIMESTAMP` for every write; "server now" = `.info/serverTimeOffset`
  sampled once against the wall clock, then advanced with `elapsedRealtime` (see
  `FirebaseServerClock`). That's the one place the wall clock is read.
- Live local position = `position + (elapsedRealtime − lastPositionUpdateTime) × speed`, while playing.
- Room expected position = `positionMs + (serverNow − updatedAt)` while playing, else `positionMs`.

## Sync rules (`DefaultSyncEngine`)

| Rule | Value |
|---|---|
| Seek detection | local jump > 3 s vs. extrapolated → broadcast SEEK |
| Drift check | every 5 s; correct only if > 1500 ms off |
| Correction rate limit | ≥ 10 s between corrections; after 3 consecutive failed seeks, timeline sync is turned off for that app |
| Echo suppression | each applied command records its expected result for 1.5 s (extended while the app is buffering, max 10 s); matching local callbacks are ignored. Room writes with `updatedBy == myUid` are ignored. A local change that already matches the room is never broadcast. |
| Conflicts | room state is authoritative; last write wins. The server stamps `updatedAt` and increments `seq` in the same write, so seq order = server-timestamp order. Lower seq than what we've seen is ignored as stale. |
| Late join / reopen app | on first snapshot from the app, seek to room expected position, then match play/pause. For 4 s after attach, the app's own start-up changes (autoplay, resume point) are not broadcast; we re-align instead. |
| Empty room | if nobody has played yet, the first member whose app settles seeds the room state. |

### Fallbacks (chosen automatically per attach)

| Detected | Mode | Behaviour |
|---|---|---|
| No session for the app, or no PLAY/PAUSE action ever seen | `MANUAL_COUNTDOWN` | "3, 2, 1, play" countdown at a shared server time |
| Position −1, or a pause after ≥ 5 s of play reports 0 / the same position, or seeks repeatedly don't take | `PLAY_PAUSE_ONLY` | play/pause only, no seek/drift/position reports, notice shown |
| No `SEEK_TO` | `PLAY_PAUSE_CATCH_UP` | play/pause; whoever is > 1.5 s ahead of the slowest member pauses for that long |
| Otherwise | `FULL` | everything |

## Firebase data model

```
rooms/{code}
  hostUid, createdAt
  title:     { service: "NETFLIX"|"JIOHOTSTAR", deepLink, setBy, setAt }
  state:     { status: "playing"|"paused", positionMs, updatedAt, updatedBy, seq }
  countdown: { requestedAt, leadMs, startedBy }
  members/{uid}: { name, online, joinedAt, lastSeen, positionMs, reportedAt }
chats/{code}/{pushId}:     { uid, name, text, sentAt }
reactions/{code}/{pushId}: { uid, name, emoji, sentAt }
```

Changes from the suggested model, and why:
- **chats/reactions are sibling trees, not under the room.** Every device listens to the whole
  room node for state changes; putting chat inside it would re-download chat history on every
  play/pause/position report.
- **`countdown`** added for the no-session fallback.
- **`lastSeen`/`joinedAt`** on members so "offline" can say since when.
- Region: create the database in **asia-southeast1 (Singapore)**, the closest Realtime Database
  location to India.

Rules: `data-firebase/firebase/database.rules.json`. Only authenticated members can read a room
or write state/countdown/position; only the host sets the title; you can only write your own
member node; joining = writing your own member node (denied if the room doesn't exist).

## Phases

0. Diagnostics screen (media-agent, ui-agent)
1. Onboarding + rooms + presence (ui-agent, backend-agent)
2. Play/pause sync + foreground service + echo suppression + conflicts (sync-engine, media, backend; Orchestrator integrates)
3. Timeline sync: extrapolation, seek detection, drift correction, late join, position reports + drift overlay
4. Title picker + Open movie + same-title confirmation
5. Chat, reactions, floating bubble, status UI

Review reports per phase: `REVIEW_LOG.md`.
