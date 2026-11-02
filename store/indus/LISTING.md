# BingeJam — Indus Appstore listing

**Publishing:** the **first** version is uploaded by hand in the Indus console (APK + everything below).
After that, `bash scripts/release.sh X.Y.Z "notes"` also pushes the APK to Indus via its API
(`scripts/indus-upload.sh`, key in `~/.syncit/indus-api.key`). Same signing key every time.

Paste these into the Indus Appstore console (https://developer.indusappstore.com). Keep them in
sync with the app and with web/privacy.html. Nothing here may show Netflix/JioHotstar/Spotify/etc.
logos or posters — their names appear only as plain text to say which apps BingeJam works with.

## Identity
- **App title:** BingeJam
- **Package:** com.syncit.app
- **Default language:** English
- **Category:** Entertainment (secondary: Social)
- **Content rating:** 18+ (the app is for ages 18 and over; see Terms)
- **Price:** Free, no in-app purchases, no ads
- **Website:** https://syncit-watchparty.web.app
- **Privacy policy:** https://syncit-watchparty.web.app/privacy
- **Account deletion:** https://syncit-watchparty.web.app/delete-account
- **Support email:** aritraditya.roy@gmail.com

## Short description (≤ 80 characters)
Watch and listen together, in sync, with friends — on the apps you already use.

(80-char backup: "Watch movies and music together in sync with friends on your own apps.")

## Long description
BingeJam keeps your movies and music in sync with friends, so you can watch and listen together
even when you're apart. Everyone plays on their own phone, in the apps they already have — BingeJam
just keeps play, pause and position lined up for everybody.

WATCH PARTIES
Start a watch party for Netflix, JioHotstar or Prime Video. When the host plays, pauses or skips,
everyone's video follows. Each person watches in the official app with their own account.

JAMS (listen together)
Start a jam on Spotify, YouTube Music, Amazon Music or YouTube. The same track plays for everyone
and follows the host from song to song.

FRIENDS, CHAT AND VOICE
Add friends by username, send messages, and see when someone's typing. Hop into voice chat inside a
room to talk while you watch or listen.

SHARE ONLY WHAT YOU WANT
Choose which apps your friends can see you using — it's off until you turn it on, app by app, and
you can go invisible any time.

YOUR CONTENT, YOUR ACCOUNTS
BingeJam never streams, records or copies any video or audio. It only syncs play/pause/position
using Android's media controls. You keep watching and listening in the official apps, signed in to
your own accounts and subscriptions.

PRIVATE BY DESIGN
No ads. No analytics trackers. BingeJam never reads your notifications, and never records your
screen, audio or video. Delete your account and data any time from Settings.

BingeJam is not affiliated with, endorsed by or sponsored by Netflix, JioHotstar, Amazon, Spotify or
YouTube. All trademarks belong to their owners. You need your own account/subscription for each app.

## Tags / keywords
watch party, watch together, sync, music together, jam, listen together, friends, chat, voice chat

## What's new (release notes for this version, 0.6.6)
- Voice chat in rooms: talk while you watch or listen
- Start a room with a friend from their profile
- Message times and typing indicators in chat
- Fresh, faster social screens

## Required assets (see the files in this folder)
- App icon 512×512 PNG: `icon-512.png` (done)
- Feature graphic 1024×500 PNG: `feature-graphic.png` (done)
- Phone screenshots (2–8, PNG/JPG): `screenshots/` — see SCREENSHOTS.md (needs a device)
- Signed APK: `dist/sync-it-indus.apk` (`./gradlew assembleRelease -Pstore=indus`; no GitHub updater, versionCode 16 / 0.6.6)

## Notes for the submission form
- **Notification Access / special permission:** Indus review will ask why the app needs Notification
  Access. Answer: "BingeJam reads the media session of supported streaming apps (play/pause/position
  and the title/artwork they publish for Android's media controls) to keep playback in sync between
  friends. It never reads notification content; it does not override onNotificationPosted." Same text
  as PLAY_STORE_READINESS.md §1.
- **Foreground service:** a quiet notification keeps sync running during a watch party/jam.
- **Data safety:** email, profile, friends, messages, activity (opt-in), push token. No ads/analytics.
  No data sold or shared. In-app deletion. Matches web/privacy.html.
