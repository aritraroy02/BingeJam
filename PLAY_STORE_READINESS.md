# Play Store readiness notes

Not blocking v1. Drafts to adapt before submitting.

## 1. Notification Access (sensitive permission)

### In-app prominent disclosure (shown before sending the user to settings)
Already in the app: `app/src/main/res/values/strings.xml` → `notification_access_disclosure`.

> Sync it needs Notification Access to see and control what's playing in Netflix or
> JioHotstar: whether it's playing or paused, where you are in the movie, and the title and
> artwork it shows in its media controls. That's how it keeps everyone in your room in sync
> and shows them what you're watching.
>
> Android only gives this ability to apps with Notification Access. Sync it never reads your
> notifications, messages or their content, and never records your screen, video or audio.

### Play Console justification (Permissions declaration / sensitive access)
> Sync it is a watch-party app: several people each play the same movie in the official
> Netflix or JioHotstar app on their own phones, and Sync it keeps playback in sync. Its core
> feature is reading the playback state (playing/paused, position) of those apps and sending
> them play, pause and seek commands. Android exposes other apps' media sessions only to apps
> with an enabled NotificationListenerService (MediaSessionManager.getActiveSessions requires
> it). Sync it uses the listener solely for that. It does not read, store or transmit
> notification content; it does not override onNotificationPosted. Without this access the
> app's core feature cannot work.

## 2. Foreground service type `mediaPlayback`
Our foreground service doesn't play media itself; it keeps media playback in another app in
sync. Expect Play's foreground-service declaration form to ask for a justification and a
video. Suggested text:
> While the user is in a watch party, a foreground service keeps the connection to the room
> and to the streaming app's media session alive so play/pause/seek stay in sync while the
> user watches in the streaming app. It runs only while the user is in a room, shows a
> persistent notification, and stops when they leave or tap "Stop syncing".

Open question: if Play rejects `mediaPlayback` for this use, the fallback is `specialUse`
with the same justification (requires the `FOREGROUND_SERVICE_SPECIAL_USE` permission and a
`<property>` describing the use).

## 3. Display over other apps (SYSTEM_ALERT_WINDOW)
Optional feature (chat bubble). The user grants it in system settings after tapping the
bubble button; the app works fully without it.

## 4. Battery optimization
We only open the system battery-optimization list (no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
permission), so no policy declaration is needed.

## 5. Privacy policy points
- We read only the **media session** of the streaming app you choose: playback state
  (playing/paused, position, speed, which controls it supports) and the title, subtitle and
  artwork it publishes for media controls. We send it play/pause/seek commands.
- If you host a room, that title and a small thumbnail of the artwork are shared with anyone
  who has the room code (shown before they join).
- **Clipboard:** on its very first launch the app checks the clipboard once for a Sync it invite
  link (left there by the invite page so a fresh install can join the room). Anything else is
  ignored; nothing is stored or sent. Android 12+ shows a "pasted from clipboard" notice.
- We **never** read notification content, and never capture, record, or transmit your screen,
  video or audio.
- We never access your Netflix/JioHotstar account, credentials, or viewing history.
- Data stored on our backend (Google Firebase, Singapore region): an anonymous user ID, the
  display name you enter, room membership and online status, the room's play/pause state and
  position, your periodically reported playback position (for sync), the title link the host
  shares, the host's now-playing title and artwork thumbnail, chat messages and reactions.
- Rooms are only readable by their members. A room's preview (host name, platform, title,
  artwork thumbnail) is readable by any signed-in Sync it user who has the code, but can't be
  listed or searched. No ads, no analytics SDKs.
- The invite web page (syncit-watchparty.web.app) loads no third-party scripts, fonts or
  trackers. The APK is downloaded from GitHub (public releases repo).
- **Update check:** once per launch the app asks the public GitHub API for the latest release
  version. No account or personal data is sent; GitHub sees the request (IP address).
  A Play Store build should drop this in favour of Play updates.
- Retention: open question. Rooms, chats and reactions currently persist until deleted.
  Add a scheduled cleanup (e.g. delete rooms idle for 24 h) before launch.
