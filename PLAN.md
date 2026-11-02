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
| `:voice` | ui-agent | Android lib | Room voice chat: `AndroidVoiceController`, `VoiceEngine` (mic/Opus/relay/mix), `VoiceService`, `AudioRouting`. |
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
| Seek detection | local jump > 3 s vs. extrapolated → broadcast SEEK. A jump seen as the app enters BUFFERING/SKIPPING is remembered and broadcast once the app settles (or after 10 s), unless it is our own echo or a room write arrives first. |
| Drift check | every 5 s; correct only if > 1500 ms off; skipped while the app is buffering (max 10 s) |
| Correction rate limit | ≥ 10 s between corrections; after 3 consecutive failed seeks, timeline sync is turned off for that app |
| Echo suppression | each applied command records its expected result; expectations are held while the app buffers (until it settles, max 10 s after the command), then get a normal 1.5 s window; matching local callbacks are ignored. Room writes with `updatedBy == myUid` are ignored. A local change that already matches the room is never broadcast. |
| Conflicts | room state is authoritative; last write wins. The server stamps `updatedAt` and increments `seq` in the same write, so seq order = server-timestamp order. Lower seq than what we've seen is ignored as stale. |
| Late join / reopen app | on first snapshot from the app, seek to room expected position, then match play/pause. For 4 s after attach, the app's own start-up changes (autoplay, resume point) are not broadcast; we re-align instead, at most `maxSettleRealigns` (2) times, then once more when the window ends (Netflix lands seeks early and autoplays, which otherwise looped). |
| Empty room | if nobody has played yet, the first member whose app settles seeds the room state. |
| Per-app seek lead | `RoomSyncCoordinator` adds `NETFLIX_SEEK_LEAD_MS` (1 s) to seeks sent to Netflix while the room plays: Netflix reports PLAYING about 1 s before the picture moves. Inside the echo tolerance (3 s) and drift threshold (1.5 s), so it isn't broadcast or corrected back. |
| Room kind | watch party vs jam = the title's app, else the preview's app, else what the host plays. While the host is alone it follows the app the host most recently started playing; it locks once anyone joins. The host never syncs an app of the other kind. |
| Item change | Host's app or title changes (new track, next episode, Netflix → Prime): preview published at once, then `onItemChanged(leader = true)`: host waits ≥ 800 ms for a settled reading, then broadcasts it as SEEK with no settle window. Members call `onItemChanged(false)` when the preview's item changes and align to the next room write (hidden by OTHER_TITLE / JamFollower until their app is on it). Falls back to late-join after 8 s. |

### Fallbacks (chosen automatically per attach)

| Detected | Mode | Behaviour |
|---|---|---|
| No session for the app, or no PLAY/PAUSE action ever seen | `MANUAL_COUNTDOWN` | "3, 2, 1, play" countdown at a shared server time |
| Position −1 while PLAYING/PAUSED (not while CONNECTING/BUFFERING: Netflix's first reading is CONNECTING −1), or a pause after ≥ 5 s of play reports 0 / the same position, or seeks repeatedly don't take | `PLAY_PAUSE_ONLY` | play/pause only, no seek/drift/position reports, notice shown |
| No `SEEK_TO` | `PLAY_PAUSE_CATCH_UP` | play/pause; whoever is > 1.5 s ahead of the slowest member pauses for that long |
| Otherwise | `FULL` | everything |

### Netflix behaviour (measured 2026-10-29, Netflix 9.89 on a Motorola Edge 60 Pro, Android 17)
- MediaSession metadata: 5 keys (title, display title/subtitle, duration, art). No MEDIA_ID, no queue,
  no launch intent, active item id −1. Movies: subtitle null; episodes: "S2:E4 - …" (no show name).
- Opening a title: CONNECTING (position −1) → PAUSED at the viewer's own resume point (actions without
  SEEK_TO) → BUFFERING → PLAYING. A seek sent before it plays is often ignored.
- Seeks land on the keyframe 0.1–5 s *before* the target, and a seek while paused starts playback.
- After a seek it reports PLAYING about 1 s before the picture moves (hence the seek lead).
- Links it opens in-app: `netflix.com/watch/<id>` (starts playing), `/title/<id>` (title page),
  `/search/<text>` (search with the text filled in; `/search?q=` ignores the text).
- Started cold (not running), Netflix closes its start-up screen ~2 s after a `/watch/` link and drops it,
  bouncing back to the room; warm, the link plays. The room resends the link once (`NetflixRetry`, 12 s window).
- Leaving the player destroys the session (status "Netflix closed on this phone"); reopening is a
  late join. The release build's logs are kept: `adb logcat -G 16M` so a test fits in the buffer.

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

## v2: social restructure ("Vyro", started 2026-10-15)

Working name **Vyro — Your world. In sync.** (`core/config/Brand.kt`; trademarked elsewhere, will
be renamed again, so the name lives only there, in `app_name` and in web/). Package stays
`com.syncit.app`. Distribution: an app store (Indus/Play), since Play Protect blocks sideloaded
apps with notification-listener access in India.

### User's decisions (2026-10-15)
- Sign-in: **Google** (Credential Manager → Firebase) or **passwordless email link**. No anonymous
  users any more; everything requires an account.
- Every user has a **unique, editable username** (suggestions offered). Internal unique code = uid.
- **Friends**: requests by username; once friends they can **chat** (DMs).
- **Activity**: a setup page explains friends will see what you play and lets you pick apps
  (e.g. Netflix yes, Spotify no). Changeable in Settings, plus "invisible".
- Apps: Netflix, JioHotstar, **Prime Video** (synced watch party: "Ask to join" / "Invite");
  Spotify, YouTube Music, Amazon Music, YouTube: first "see + open only", then (same day, after
  testing) **jams**: synced listening, see "Jams" below.
- ~~In-app only notifications~~ (2026-10-28): phone notifications for new messages and friend
  requests through FCM, sent by `push-relay/` (Cloudflare Worker, free plan) because Cloud
  Functions need Blaze. The app pings the relay after writing; the relay verifies the Firebase ID
  token and reads the message/request itself, so clients can't choose the notification text.
- (2026-10-29) **Join requests and invites pop up** as notifications (relay types `join_request` /
  `invite`, Android channel `requests`, IMPORTANCE_HIGH), because hosts inside Netflix never saw the
  in-app prompt. A tap opens Home, where the request banner is.
- (2026-10-29) **New-version notifications**: the relay's cron (every 30 min) reads the latest release
  of `aritraroy02/sync-it-releases`; when its version changes it sends one data-only FCM message to topic
  `updates` (every install subscribes at start-up). The app shows "BingeJam X is available" only if X
  is newer than the installed version; a tap opens Home (update banner). KV `release:announced` holds the
  last announced version; the first run only records it (so seed it with the previous version before
  the first release that should be announced: `wrangler kv key put "release:announced" "<previous>"
  --namespace-id <AVATARS id> --remote`). Only installs from 0.6.1 on subscribe to the topic.
- (2026-10-29) **"Watching with"**: friends see "In a watch party with Somesh" (the online room
  members' display names, already readable by any signed-in user). Still never the room code.
- (2026-10-29) **Quit** (sync notification, since 0.5.1): stops sync, chat bubble, presence and activity
  sharing, unbinds the notification listener (`requestUnbind`, so Android stops keeping the process
  alive) and ends the process. A persisted `quit` flag keeps presence off if a push restarts the process;
  opening MainActivity clears it and calls `requestRebind`. Room membership is kept (shown offline).

### Contracts (`:core`)
- `auth/AuthService`, `social/ProfileRepository`, `social/FriendsRepository` + `DirectMessages`,
  `social/Presence.kt` (`PresenceRepository`, `ActivityTracker`, `WatchInvites`).
- Models: `model/Social.kt`, `model/ActivityApp.kt` (`MediaKind`), `StreamingService.PRIME_VIDEO`.

### Data model (Realtime Database)
```
users/{uid}:              { username, displayName, photoUrl?, bio?, createdAt }      signed-in read, owner write
users_private/{uid}:      { email?, provider, createdAt }                             owner only
usernames/{key}:          uid   key = username with '.' → ',' (RTDB keys can't hold '.'; same sort order)
                                                                                      create-if-absent, owner delete
friendRequests/{to}/{from}: { fromUsername, fromDisplayName, toUsername, toDisplayName, sentAt }
sentFriendRequests/{from}/{to}: true   index for listing outgoing requests (RTDB can't query */{me});
                                       written/deleted in the same multi-path update as the request
friends/{uid}/{friendUid}: { since }                                                 written pairwise on accept
blocked/{uid}/{otherUid}: true                                                        owner only
dms/{dmId}/{msgId}:       { from, text, sentAt, forwarded? }   dmId = sorted uids "a_b"; only the two, only friends
                          forwarded: true (create only). Delete for everyone (sender only, once, 2026-11-02):
                          { from, sentAt, deleted: true }, text + forwarded removed; nobody else edits a message
dms/{dmId}/w-{CODE}-{hash8}[-n]: { kind: "watch", by, title, service, durationMs, items, sentAt, lastAt }
                          "watched together" card (2026-11-02): either participant creates/extends it; durationMs
                          (5 min..24 h) and items only grow; by/title/service/sentAt fixed; no from/text, so it's
                          never unread and the relay never notifies for it. See "DM message actions + watched together"
dmHidden/{uid}/{dmId}/{msgId}: true   "delete for me" (2026-11-02), owner read/write only; in account deletion
dmReads/{uid}/{friendUid}: lastReadAt                                                owner only
typing/{dmId}/{uid}:      server timestamp while typing (≤ every 3 s); readers treat > 6 s as stopped; onDisconnect removes
presence/{uid}:           { online, lastSeen, activity? { app, title, subtitle, artwork, playing,
                            durationMs, startedAt, inWatchParty, watchingWith? } }   friends read, owner write
                          watchingWith: "Somesh and Ayan" (≤ 100 chars, only while inWatchParty; rules 2026-10-29)
                          onDisconnect clears online AND activity (re-published on reconnect)
joinRequests/{host}/{from}: { fromUsername, fromDisplayName, app, title, status, roomCode?, sentAt }
invites/{to}/{from}:      { fromUsername, fromDisplayName, roomCode, app?, title?, sentAt,
                            declined?, answeredAt? }   the invitee may only add declined = true + answeredAt
                          (server time), so the inviter sees "Not now"; either side deletes (2026-10-29)
pushTokens/{uid}/{fcmToken}: { updatedAt }                                            owner only; read by push-relay (admin)
users/{uid}/photoUrl:     push-relay avatar URL (…/avatar/{uid}?v=…) or a Google photo; rules allow only those
KV avatar:{uid} (Cloudflare, not RTDB): picture bytes, GIF ≤ 1 MB, still ≤ 300 KB, one upload per 20 s
KV nflx:{normalized title}|{minutes}: {netflixId|null, source}  Watchmode answers, 25 days found / 3 days missing
KV release:announced:     last version announced on FCM topic "updates"
FCM topic updates:        data-only {type: "update", version, notes}
rooms/…, previews/…, chats/…, reactions/… (v1, unchanged; previews.service also allows PRIME_VIDEO)
rooms/…, previews/…, chats/…, reactions/… (v1; service also allows PRIME_VIDEO + the 4 jam apps;
                          previews/{code}/playableRef optional, strict public formats only)
```
Account deletion (2026-10-29, `AuthService.deleteAccount`, `FirebaseAccountEraser`): one atomic multi-path
null update of the user's own nodes, mirror entries on connected users, whole DM threads and the active room
membership/preview and `typing/{dmId}`, then the avatar (Worker DELETE /avatar) and the Auth user. Rules: owner-only delete-only
rules on whole own nodes; `dms/$dmId` may be nulled by a participant only in the same write that deletes their
`users/{uid}`; `usernames/$name` deletable when the owner's profile goes in the same write. Sign-in must be
< 4 min old (ID-token auth_time) or `RecentLoginRequiredException`. Room chats/reactions/older rooms remain.
All v2 social paths require a real (non-anonymous) account; v1 room paths still accept anonymous
users so v0.2.x installs keep working until they update (Anonymous provider stays enabled until then).

### Flows
- First run: sign in → pick username (suggestions) → **Activity setup** page → permissions → Home.
- Home: friends list with live activity ("Watching S2:E4 on Netflix", "Listening to … on Spotify"),
  requests, chats, plus create/join room as before.
- Movie activity: friend taps **Ask to join** → host gets a pop-up notification and the in-app prompt →
  Accept creates/uses the host's room and returns the code → requester lands in the room (existing
  sync + Play button). Host can also **Invite** a friend from the room (also a pop-up).
- **Start a room with <friend>** (friend's profile sheet, 2026-10-29): creates a room (or uses the one
  we're syncing in), invites them (pop-up) and opens it showing "Waiting for <friend> to join…" with
  Cancel. Their **Join** deletes the invite and joins; **Not now** marks it declined and the host sees
  "<friend> can't join right now". Invites sent from the room's Invite sheet get the same banner.
  Leaving the room withdraws pending invites. Older apps delete on "Not now", so a gone invite with
  no new member gives up after 30 s ("didn't join").
- Room **Play in Netflix** (member): the host's exact movie when its ID is known, else the host's link,
  else Netflix search with the title, else the app (see "Netflix exact title").
- Music/YouTube activity: **Ask to jam** / **Invite to jam** (same request/invite flow), see "Jams".

### Jams (music + YouTube rooms, 2026-10-15)
- `StreamingService` covers all 7 apps; `kind`/`isJam` (MUSIC, VIDEO) marks jam rooms.
- Host publishes `previews/{code}/playableRef`: a public link to the exact item, only
  `spotify:(track|episode):<22>` or `https://(www|music).youtube.com/watch?v=<11>` (rules + `PlayableRefs`
  + `:media` `PlayableRefParser` agree). Amazon Music has no known public format → title search.
  Never a private stream URL (JioHotstar's MEDIA_URI is never read).
- Member (`JamFollower`): if their app isn't on the host's track, one automatic attempt per host
  track: `playFromUri(ref)` → `playFromSearch("title subtitle")`; STARTING until it matches, FAILED
  after 6 s. No session → NEEDS_APP: the room screen opens the app on the item once per track
  (`JamAutoOpen`). Member skips away → FAILED after 5 s, room unaffected; "Back to the jam" =
  `SyncSessionController.followHost()`. Mismatched sessions are hidden from the engine.
- Host track change: preview published immediately, then `engine.onItemChanged(leader = true)`;
  members call `onItemChanged(false)` when the preview's track key changes.

### Netflix exact title (2026-10-29)
Netflix publishes no content ID (see "Netflix behaviour"), so the room couldn't open the host's movie.
- `:core` `lookup/TitleLookup.netflixId(title, durationMs)`; `:data-firebase` `RelayTitleLookup` calls the
  relay's `GET /netflix` with the user's Firebase ID token and remembers answers per process.
- Relay: Watchmode `/search` (exact normalized name, movie/tv_movie/tv_special, ≤ 3 candidates) →
  `/title/{id}/details?append_to_response=sources&regions=IN` → runtime within 15 min of Netflix's
  length → Netflix (source 203) `web_url` `netflix.com/title/<digits>`. Cached in KV (see data model).
  Watchmode free plan: non-commercial, credit required, 2,500 credits/month, ≤ 3 countries.
- App: `RoomViewModel.netflixId` (members only, movies only: no "S1:E2"-style subtitle) prefetches the ID;
  `TitleLinks.openNetflix` opens `netflix.com/watch/<id>` pinned to Netflix (digits only), else the host's
  link, else `netflix.com/search/<title>`, else the app. The room shows "Movie found with Watchmode".
- Measured coverage (IN): found The Paradise, Squid Game, Wednesday, Leo, Animal, Maharaja; missing
  Extraction, Red Notice, Glass Onion, RRR, Jawan (those fall back to search).

### JioHotstar exact title (2026-11-02)
JioHotstar publishes no MEDIA_ID, but its MEDIA_URI is the stream URL, e.g.
`https://hses9.hotstar.com/gsvod1/movies-prod-origin1/videos/hotstarint/v3/1271713346/1791213049860/<hash>`;
the segment after `/v3/` is the public content ID (measured with "October" on the OPPO, JioHotstar
playing; `https://www.hotstar.com/in/movies/x/1271713346/watch` opened October and started it, also with
JioHotstar force-stopped, and a `/in/shows/x/<id>/watch` path worked too: the app ignores the type/slug).
- `:media` `PlayableRefParser` reads JioHotstar's MEDIA_URI and keeps **only** the content ID, as
  `https://www.hotstar.com/in/movies/x/<6-12 digits>/watch` (the stream URL is never stored or shared).
  `PlayableRefs` + the `previews/$code/playableRef` rule accept that form.
- Room: "Play in JioHotstar" opens that link in JioHotstar (`openJioHotstar`), else the host's link, else
  the app; no search. JioHotstar resumes at its own saved position; the sync session then jumps to the
  room's position. Episodes: untested (the content ID should be the episode's).

### In-room bar (2026-11-02)
Back from the room keeps you in it (sync + voice keep running). `OngoingRoomBar` (WhatsApp-style ongoing
call bar) sits above every other screen while `SyncSessionController.state.roomCode` is set: "In a room
05:12" (time since entering, `UserPrefs.roomJoinedAt`, kept across restarts), mute while in voice, Exit
(confirm, then the same as Leave: voice, sync, `leaveRoom`). Tapping it returns to the room's existing
back-stack entry (`NavViewModel.openRoom`). Screens under the bar don't add status-bar padding again.
Home's top bar no longer shows the wordmark (user's request).

### Bottom tabs + Chats (2026-11-02)
User's request: a floating glass bar at the bottom with four icons only (Home, Chats, Add friends, Settings);
Friends and Settings left the Home top bar; the "Watch party or jam" card (Start / Join with code / rejoin) is
gone (rooms start from a friend's sheet, "Start a room with …", or an invite). Home will later suggest trending
movies across platforms.
- `ui/nav/MainTabBar.kt`: `MainTab`, the bar (badges: unread messages, friend requests), `LocalBottomBarInset`
  (tab screens pad their scroll content by `TabBarInset`). Glass blurs the screens underneath (Haze source on
  the screen container in `MainNav`).
- Tabs are single-entry back stacks (`nav.reset(tab)`); the bar shows only when the stack is one tab screen.
  Back on another tab goes to Home. Friends/Settings show a back arrow only when opened from somewhere else.
- **Chats** (`ui/chat/ChatsScreen.kt`): one row per friend we've messaged, newest first: avatar (online dot),
  name, time ("10:42 pm" today, else "Yesterday"/weekday/date), last message ("You: …", "This message was
  deleted", "Watched Leo together"), unread badge. Data: `DirectMessages.observeLatest()`: per friend
  `dms/{dmId}` orderByChild(sentAt).limitToLast(5), skipping `dmHidden` ("deleted for me").
- DM notification taps open Chats → the chat, so Back lands on the Chats list.
- Chats has a search box over all friends (name or username, "@" ignored; names starting with it first);
  tapping a result opens or starts that chat.
- Home no longer lists friends (user's request): only "Live now" (friends playing something; tap → friend
  sheet with Start a room / Ask to join), the update banner, and a placeholder when nobody is live.

### Trending on Home (2026-11-02)
Home shows "Trending on Netflix / JioHotstar / Prime Video" poster rows, updated automatically.
- Relay `push-relay/src/trending.js`: `GET /trending` = TMDB `/discover/movie` per provider in India (TMDB
  provider IDs: Netflix 8, JioHotstar 2336, Prime Video 119; `with_watch_monetization_types=flatrate`),
  popularity order, Indian-origin and all titles interleaved, 20 per row, posters `image.tmdb.org/t/p/w342`.
  Cached in KV `trending:v1`, refreshed when > 12 h old (cron every 30 min, or the next request).
  `GET /watch-link?tmdb=&service=` = Watchmode `/title/movie-<tmdbId>/details?append_to_response=sources&regions=IN`
  (exact match by TMDB ID) → Netflix `netflix.com/watch/<id>`, JioHotstar `hotstar.com/in/movies/x/<id>/watch`,
  Prime Video its `primevideo.com/detail/…` page; KV `wlink:<service>:movie-<id>` 25 days (3 if missing).
  Both signed-in only. Secret `TMDB_READ_TOKEN` (TMDB "API Read Access Token").
- App: `TrendingCatalog` (`:core`), `RelayTrending` (`:data-firebase`, links checked again by
  `TrendingCatalog.isSafeLink`). Tap a poster → sheet: **Watch with friends** (room created or reused, the
  title's link set as the room title, room opens on "Invite friends"; the host gets "Play in <app>" while not
  playing, `MemberPrompts.of(hostHasLink)`), **Watch alone** (opens the link; Netflix falls back to search).
- Credits: "Movie data from TMDB · Where to watch by JustWatch" under the rows; TMDB notice in Settings →
  Licences. TMDB and Watchmode free plans are non-commercial.
- Unverified: Prime Video detail links opening in the Prime app.

### Agents
- backend-agent (`:data-firebase` + rules + emulator tests), media-agent (`:media`: ActivityTracker,
  Prime Video + music/YouTube session checks on the phone), ui-agent (`:app`: auth, username,
  activity setup, friends, DMs, activity feed, join/invite, rename), reviewer-agent at the end.
  The Orchestrator owns `:core`, docs, Firebase console/deploys, releases.

## Invite + now-playing features (noted 2026-10-06, built 2026-10-07)

Built 2026-10-07 with the user's choices: APK download from the invite page (no Play listing
yet), clipboard handoff for auto-join (no Install Referrer dependency), title + artwork read
from the player's MediaSession, preview visible **only inside the app** (the web page shows
just the code). Data: `previews/{code}` {hostName, service, title, subtitle, artwork (base64
JPEG, at most 52 KB), updatedAt}, written by the host's RoomSyncCoordinator, readable by any
signed-in user. The original notes below are kept for context.

### A. Invite link installs the app and auto-joins the room
Today: `syncit://join/CODE` (custom scheme). Many messengers don't make it clickable, and it
does nothing if Sync it isn't installed.
Wanted: one link that opens Sync it and joins the room if installed, otherwise sends the
person to install it and then **joins automatically after install + onboarding**.
- Link: https App Link on a domain we control, e.g. Firebase Hosting
  `https://syncit-watchparty.web.app/join/CODE`, with `/.well-known/assetlinks.json`
  (needs the signing cert SHA-256) and `autoVerify` intent filter for that path.
- Not installed: the web page shows the room code and an install button (Play Store URL
  with `&referrer=room%3DCODE`, or an APK download until there's a Play listing).
- Auto-join after install: Firebase Dynamic Links is shut down, so use the **Play Install
  Referrer API** (`com.android.installreferrer`, a new dependency, so ask first) to read the code
  on first launch. Before a Play listing this can't work, so fall back to "paste the
  code" with the code copied from the page.
- App side: keep the pending room code through onboarding (permissions + name), then join
  automatically. Keep `syncit://` working.

### B. Invitee sees what's playing and on which platform
Show the movie name + platform (Netflix / JioHotstar) on the invite page and on the join
screen before joining, and keep it visible in the room.
- Today `title` stores only `service` + `deepLink` (no human-readable name), and rooms are
  readable only by members, so a non-member can't preview anything.
- Name source options: host types it; or the streaming app's own MediaSession metadata
  (`MediaController.getMetadata()` → `METADATA_KEY_TITLE`). That is session metadata,
  not notification content, so it fits the hard rules, but **update the privacy policy
  points** in PLAY_STORE_READINESS.md. Check on the device what Netflix/JioHotstar actually
  expose.
- Preview data: a small public-to-signed-in node, e.g. `invites/{code}: {service, titleName,
  hostName}`, written by the host, readable by any authenticated user (the code itself is
  the secret). Rules + emulator test needed. The web page would need it readable without
  auth, so decide what is OK to show publicly.

## DM message actions + watched together (built 2026-11-02)

**Long-press a DM** (haptic) → glass sheet: **Copy** (clipboard; our "Message copied" notice only below
Android 13, which shows its own), **Forward** (pick friends with avatars, multi-select; each gets a normal
`DirectMessages.send(…, forwarded = true)`, so the relay notifies as usual; "Forwarded" label above the text),
**Delete** → "Delete for everyone" (own, not yet deleted messages) / "Delete for me" / Cancel.
- Delete for everyone: `updateChildren(text = null, forwarded = null, deleted = true)` on the message. The
  node keeps `from` + `sentAt`, so order and unread counts don't move; both sides show "This message was
  deleted" ("You deleted this message" for the sender). Rules: only the sender, only from a message that
  still has text, only to `deleted: true` without text/forwarded; no un-delete, no edits, no node removal.
- Notifications: the relay reads the message itself, so a deleted message (no text) or a card never
  notifies. A notification already shown is retracted: the app pings the relay with `type: "dm_deleted"`;
  if that message is still the sender's latest (no later message from them), the relay sends a
  data-only FCM message and the app replaces the notification tagged `dm-<uid>` with "This message was
  deleted", silently, only if it's still showing (`PushNotifications.retractChat`). Messages older than
  24 h aren't retracted. Older relays answer 400 (logged, nothing else).
- Delete for me: `dmHidden/{me}/{dmId}/{msgId} = true`; `observe()` filters it (all my devices), the
  friend never sees it. Cards can be hidden too (their menu only has Delete → Delete for me).
- Room chat (ChatTab): long-press copies the message (no menu).
- Old app versions: deleted messages vanish for them (their parser needs text), cards are skipped,
  forwarded messages show without the label. No chat-list preview of the last message exists, so there
  was nothing to update there.

**Watched together cards**: "Watched Leo together · Netflix · 1h 42m" (jams: "Listened to Blinding
Lights + 3 more together · Spotify · 45m"; YouTube says "Watched") as a centred glass card in the DM
timeline, for both people, once per session.
- Where: `WatchTogetherRecorder` (`app/social/`), created in AppContainer (process lifetime), driven by
  `SyncSessionController.state`, so it doesn't depend on the room screen being visible. While the session
  has a room it observes `rooms/{code}` (members, state), `previews/{code}` (title, service) and our
  friends; it samples on every change and every 30 s.
- Together = we're on the room's title (session status SYNCING/CORRECTING/CATCHING_UP), the room state is
  `playing`, the preview names a title, and the friend is a room member who is online, a friend, and (if
  they report positions) reported within 60 s. Only friends get cards (only friends have DMs); a room
  with 4 people writes a card into each friend pair's DM, from both phones of the pair.
- `WatchTogetherTracker` (pure) adds up time per friend with elapsedRealtime (gaps capped at 90 s, so a
  frozen process doesn't count). First write once a pair has 5 min together, then every 5 min while
  together, and once more when they stop (pause, leave, title change, friend gone). Apart > 2 h = new
  session.
- Dedupe: session key `w-{roomCode}-{fnv1a32(service|normalized title)}` (jams: no title, the whole jam is
  one session; `items` counts tracks). Both phones derive the same key and walk the same slots: key,
  key-2, key-3, … A slot whose card was updated within 2 h (`lastAt`, server time) is joined (duration =
  max of the card and ours, items = max), an older one is skipped, an empty one is created. Two phones
  creating at once: the rules reject the second create (by/sentAt can't change) and it re-reads and joins.
  The returned duration is adopted by the tracker, so a restarted app continues from the card's value.
- No push (the relay is never pinged for cards and ignores `kind`), never unread (no `from`).
- Rules (2026-11-02, need deploying): see database.rules.json `dms/$dmId/$messageId` and `dmHidden`.
  Emulator check (68 cases: create/extend/shrink/fields/keys/outsiders/ex-friends, delete/undelete,
  hide) passed locally. **Deploy the rules before releasing this**: account deletion now also clears
  `dmHidden/{uid}` and would be refused by the old rules.
- Known limits: a room member counts while online even if their app is in a fallback mode without
  position reports; the card's time is when it was created (5 min into the session), not the start;
  the same title in the same room within 2 h of the last card merges into that card.

## Voice chat in rooms, HD / SD (built 2026-10-29, Discord-style)
Our own stack, no paid service: phone ↔ our relay ↔ phones.
- **Phone (`:voice`)**: mic (`AudioRecord`, VOICE_COMMUNICATION + echo canceller/noise suppressor/AGC) →
  energy VAD (`core/voice/VoiceActivity`; only speech is sent, the bar rises while friends' voices play
  so their echo isn't sent back) → Opus via Android's own `MediaCodec` (Android 10+) → 40 ms per
  WebSocket message (`core/voice/VoiceWire`). Receive: one `MediaCodec` Opus decoder per speaker →
  `JitterBuffer` (starts at 80 ms queued, never > 400 ms behind) → mixed with per-person gain →
  `AudioTrack` (voice-communication usage). `AudioRouting`: MODE_IN_COMMUNICATION, headset if
  plugged in, else loudspeaker. `VoiceService`: foreground service type microphone (mic keeps working
  while Netflix is in front) with Mute / Leave in the notification.
- **Relay (`push-relay/src/voice.js`)**: `GET /voice/<code>` WebSocket, Firebase ID token, members of
  `rooms/<code>/members` only, max 8. One Durable Object per room (free plan: SQLite-backed,
  hibernatable sockets; 100k requests/day, 20 incoming messages = 1 request; outgoing free). It only
  forwards bytes (adds the sender's slot); nothing is stored. `GET /voice/echo` = hear yourself.
- **Controls**: Mute, Deafen (hear nobody + muted; undeafen restores the earlier mute), my mic volume
  0–200 %, each person's volume 0–200 % (local only), HD (48 kHz, 32 kbps) / SD (16 kHz, 12 kbps), default
  HD on Wi-Fi and SD on mobile data, last pick remembered. Settings live in SharedPreferences "voice".
- **Settings → Voice chat**: pick the Microphone (phone mic, headset/USB/Bluetooth mics) and the Speaker
  (loudspeaker, earpiece, headphones, Bluetooth), or Automatic (headset if connected, else phone mic +
  loudspeaker). Ids are kind + product name, so a headset picked once is used whenever it's back; a
  Bluetooth mic also makes that headset the call device. Before Android 12 only loudspeaker/earpiece.
- **Others see it**: `rooms/{code}/members/{uid}/voice: true` while connected (onDisconnect removes it);
  the room shows "Harsh is in voice · Join". Rules allow the boolean (2026-10-29).
- **Free-plan budget**: ~100k requests/day ≈ 2M incoming messages ≈ 22 hours of someone talking per
  day across all rooms (silence costs nothing). Past that, joins fail until 00:00 UTC (05:30 IST).
- **Known limits / next**: Bluetooth headsets switch to call mode (lower quality) while in voice;
  MODE_IN_COMMUNICATION can make the movie a bit quieter on some phones; no push-to-talk yet;
  "Test mic" (echo) is in the relay but not in the UI yet.
