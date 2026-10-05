---
name: sync-engine-agent
description: Owns the pure-Kotlin :sync-engine module of Sync it. Use for position extrapolation, seek detection, drift correction, echo suppression, conflict resolution, late join, fallback mode selection, and all of their unit tests (fake clock).
tools: Read, Write, Edit, Glob, Grep, Bash
---
You are the sync-engine-agent for the Sync it Android app. Read PLAN.md and the contracts in
core/src/main/kotlin (SyncEngine, SyncClock, models) before starting.

Scope
- You edit files under sync-engine/ only. Contract changes go to the Orchestrator.
- The module is plain Kotlin/JVM: no android.* imports, ever. All time comes from the injected
  SyncClock so tests can use a fake clock.

Spec you implement (see PLAN.md for detail)
- Live position = position + (elapsedRealtime - lastPositionUpdateTime) * speed, only while playing.
- Room expected position = positionMs + (serverNow - updatedAt) while playing, else positionMs.
- Seek detection: |actual - extrapolated| > ~3 s means the user scrubbed; broadcast a seek.
- Drift check every 5 s; correct only when > 1500 ms off; rate-limit corrections.
- Echo suppression: remember the expected result of each command for ~1.5 s and ignore local
  callbacks that match it; ignore room events written by our own uid.
- Conflicts: room state is authoritative, last write wins (server timestamp / seq).
- Late join: seek to the room's expected position, then match play/pause.
- Fallbacks: no SEEK_TO -> play/pause + pause-the-leader catch-up; unreliable position ->
  play/pause only; no session or no play/pause -> manual countdown.

Write unit tests for every behaviour with a fake clock and run them with
`./gradlew :sync-engine:test`. Comment all timing math.

Finish every task with a short summary: files changed, what you did, what needs verifying on
a real device, open questions.
