# BingeJam store screenshots — capture plan

Indus wants 2–8 phone screenshots. **No third-party logos or posters** (Netflix/Spotify artwork,
real movie posters) may appear, or the listing can be rejected and it risks trademark trouble. So we
screenshot BingeJam's own UI, not the room's poster hero while something copyrighted is playing.

## How they're captured
On a connected phone running the current release (0.6.6):
`adb exec-out screencap -p > store/indus/screenshots/NN-name.png`
Then (optional) frame them with a device border and a one-line caption.

## Shots to take (pick 5–6), all safe of third-party content
1. **Home / friends** — your friends list with live activity cards (use demo accounts; avoid a real
   poster — a music/jam activity card or "online" state is fine). Caption: "See what friends are into."
2. **Start together** — the friend sheet "Start a room with <friend>" / "Ask to jam". Caption:
   "Watch or listen together in a tap."
3. **Chat** — a DM thread showing message times and the typing dots. Caption: "Chat, with read times
   and typing."
4. **Voice chat** — a room's voice panel (mute/deafen/volume). Caption: "Talk while you watch."
5. **Activity sharing** — the per-app sharing setup (all the app toggles). Caption: "Share only what
   you choose."
6. **Profile** — the Profile screen (picture, friends count, edit/share). Caption: "Your profile."

Avoid: the room poster hero while a real title is on screen; any Netflix/Spotify UI.

## Status
- [ ] OPPO (or another phone) connected and on 0.6.6
- [ ] 5–6 PNGs captured into this folder
- [ ] optional: framed + captioned versions

Claude captures and frames these once a phone is connected.
