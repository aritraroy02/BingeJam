# BingeJam desktop (Windows)

Electron + React + TypeScript (electron-vite). Same Firebase project, users, friends, chats and rooms as
the Android app; see PLAN.md "Desktop app + browser extension" for the full plan and rules.

## Run
```
cd desktop
npm install
npm run firebase-config   # once per machine: writes the git-ignored Firebase Web config
npm run dev               # app with hot reload
```
- Install on this PC (no admin, no installer): `npm run build && npm run install:local` puts it in
  `%LOCALAPPDATA%\Programs\BingeJam` with Start menu and desktop shortcuts; run it again to update
  (quit the app first), `node scripts/install-local.mjs --uninstall` removes it.
- `BJ_DEMO=1` shows the design's sample content without Firebase (store screenshots, design reviews).
- `BJ_SNAPSHOT=C:\path\shot.png` saves a screenshot of the window after it settles and quits
  (`BJ_SNAPSHOT_DELAY` ms, default 2500). Combine with `BJ_DEMO=1` to compare with `design/*.png`.
- Started from a VS Code terminal? The npm scripts clear `ELECTRON_RUN_AS_NODE`, which VS Code sets and
  which makes Electron start as plain Node. Running `npx electron .` directly needs
  `env -u ELECTRON_RUN_AS_NODE` (bash) first.

## Layout
| Path | What |
|---|---|
| `design/` | The first mock-ups (1440×900): Home, 1:1 chat, group chat, room, Go Live, Add to browser. Layout reference only: the look changed in the 2026-11-12 unslop (see `styles/`) |
| `src/main/` | Electron main process: window, single instance, `bingejam://` links, IPC |
| `src/preload/` | `window.bingejam` bridge (typed in `bridge.d.ts`) |
| `src/renderer/src/styles/` | Design tokens and components, the source of truth for the look since the 2026-11-12 unslop: warm near-black ramp, one solid red accent, the two friend dots for live/in sync, glass only on floating layers. Same direction as the Android app and the website |
| `src/renderer/src/ui/` | Icons, primitives (Button, Avatar…), navigation, backdrop, view models |
| `src/renderer/src/screens/` | Screens; they take view models as props |
| `src/renderer/src/lib/` | Firebase setup and sign-in (email link, same provider as the phone) |
| `src/renderer/src/demo/` | Demo-mode sample data |
| `src/shared/` | Code shared by main, renderer and (later) the extension |

## Firebase
Web app "BingeJam Desktop" (`1:289263837364:web:26eb0e945f65dc8f8d77e1`) in `syncit-watchparty`, registered
2026-11-08. Its config file `src/renderer/src/firebase-config.json` is git-ignored like `google-services.json`.
Everything the desktop writes must pass `data-firebase/firebase/database.rules.json`.
