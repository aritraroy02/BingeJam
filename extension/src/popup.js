// BingeJam popup. All state lives in the background; this only draws it and sends taps back.
'use strict'

const api = globalThis.browser ?? globalThis.chrome
const $ = (id) => document.getElementById(id)
const SERVICE_NAMES = { NETFLIX: 'Netflix', PRIME_VIDEO: 'Prime Video', JIOHOTSTAR: 'JioHotstar' }

const port = api.runtime.connect({ name: 'bj-popup' })
port.onMessage.addListener((msg) => {
  if (msg?.t === 'state') render(msg.state)
})

$('play').addEventListener('click', () => port.postMessage({ t: 'toggle-play' }))
$('open').addEventListener('click', () => {
  port.postMessage({ t: 'open-app' })
  setTimeout(() => window.close(), 150)
})
for (const sw of document.querySelectorAll('button[role="switch"]')) {
  sw.addEventListener('click', () => {
    const value = sw.getAttribute('aria-checked') !== 'true'
    sw.setAttribute('aria-checked', String(value)) // instant feedback; the background confirms
    port.postMessage({ t: 'setting', key: sw.dataset.key, value })
  })
}

function clock(ms) {
  if (ms == null || !Number.isFinite(ms)) return ''
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

const LINK_TEXT = {
  linked: ['Linked to the app', 'ok'],
  connecting: ['Connecting to the app…', ''],
  idle: ['Connecting to the app…', ''],
  'app-closed': ['App not running', 'warn'],
  'no-host': ['App not installed', 'warn'],
  forbidden: ['Update the BingeJam app', 'warn']
}

function render(s) {
  const [linkText, linkClass] = LINK_TEXT[s.link] ?? LINK_TEXT.idle
  const link = $('link')
  link.textContent = linkText
  link.className = `link ${linkClass}`

  const tab = s.tab
  const room = s.link === 'linked' ? s.room : null
  const line = $('room-line')
  const title = $('room-title')
  const sync = $('room-sync')
  const syncText = $('room-sync-text')
  sync.hidden = false
  sync.className = 'room-sync'

  if (room) {
    line.textContent = room.people ? `Room ${room.code} · ${room.people}` : `Room ${room.code}`
    title.textContent = room.title || tab?.title || 'Pick something to watch'
    syncText.textContent = `${room.inSync ? 'In sync' : 'Catching up'} · ${clock(room.positionMs)}`
    if (!room.inSync) sync.className = 'room-sync warn'
  } else if (s.link === 'linked') {
    line.textContent = 'Not in a room'
    title.textContent = tab?.title || 'Start or join a room in the app'
    syncText.textContent = tab ? `Your ${SERVICE_NAMES[tab.service] ?? ''} tab · ${clock(tab.positionMs)}` : 'Open Netflix, Prime Video or JioHotstar'
    sync.className = 'room-sync off'
  } else if (s.link === 'no-host' || s.link === 'forbidden') {
    line.textContent = 'BingeJam for Windows'
    title.textContent = s.link === 'forbidden' ? 'Update the app to link' : 'Install the app, open it once'
    syncText.textContent = 'The app keeps everyone in sync'
    sync.className = 'room-sync off'
  } else {
    line.textContent = tab ? `${SERVICE_NAMES[tab.service] ?? 'Streaming'} tab` : 'BingeJam app'
    title.textContent = tab?.title || 'Open BingeJam on this PC'
    syncText.textContent = s.link === 'app-closed' ? 'Open the app to sync this tab' : 'Looking for the app…'
    sync.className = 'room-sync off'
  }

  // Seek blocked / player problems: one line the person can act on.
  if (tab?.problem) {
    sync.className = 'room-sync warn'
    if (tab.problem.code === 'play-blocked') syncText.textContent = 'Click the video once so BingeJam can play it'
    else if (tab.problem.code === 'player-error') syncText.textContent = 'The player stopped: reload the tab'
    else if (tab.problem.code === 'seek-blocked') syncText.textContent = `Seek to ${clock(tab.problem.atMs)} in the player`
  }

  const play = $('play')
  play.disabled = !tab
  play.textContent = tab && !tab.playing ? 'Play for all' : 'Pause for all'
  const open = $('open')
  open.textContent = s.link === 'no-host' || s.link === 'forbidden' ? 'Get the app' : 'Open app'

  for (const sw of document.querySelectorAll('button[role="switch"]')) {
    sw.setAttribute('aria-checked', String(!!s.settings?.[sw.dataset.key]))
  }
}
