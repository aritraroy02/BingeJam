// BingeJam content script: watches the site's <video> and applies play / pause / seek / speed.
//
// Rules (CLAUDE.md): standard HTMLMediaElement APIs only (play(), pause(), currentTime, playbackRate)
// and the page's visible DOM / navigator.mediaSession for the title. Never the site's private player
// objects (no window.netflix…videoPlayer), never video or audio data.
//
// Injected on the whole site because Netflix, Prime Video and JioHotstar are single-page apps: a click
// from the browse page to a title doesn't load a new page, so a script that only matched /watch/ would
// never run. Off the watch page it stays idle: no DOM reads, no messages.
(() => {
  'use strict'

  const api = globalThis.browser ?? globalThis.chrome
  // Test harness only (scratch page that stubs `chrome`). Pages can't set this: content scripts run in
  // an isolated world (Chromium) / behind Xray wrappers (Firefox).
  const TEST = globalThis.__BJ_TEST__
  const loc = () => (TEST ? TEST.location : location)

  // ---------- Timing (all local, performance.now(); the app converts to its own clock) ----------
  const TICK_MS = 500 // find the video, check the page
  const HEARTBEAT_MS = 1000 // a snapshot at least once a second while a video is present
  const INFO_MS = 2000 // re-read the title
  const SEEK_VERIFY_MS = 2500 // a seek that hasn't landed by then counts as failed
  const SEEK_TOLERANCE_MS = 2000 // landed = within 2 s of where it should be now
  const SEEK_SKIP_TEST_MS = 1500 // seeks this small don't tell us whether seeking works
  const RATE_VERIFY_MS = 1000 // a playbackRate the page put back within 1 s didn't stick
  // Seek fallback (seeking blocked): move with speed instead. Up to 1.1x / 0.9x (pitch is preserved by
  // the browser); gaps beyond NUDGE_LIMIT_MS would take minutes, so the person seeks by hand.
  const NUDGE_TICK_MS = 250
  const NUDGE_MAX_RATE_DELTA = 0.1
  const NUDGE_GAIN_MS = 10000 // rate - 1 = gap / 10 s (capped): exponential catch-up, ~10 s time constant
  const NUDGE_DONE_MS = 150
  const NUDGE_LIMIT_MS = 20000
  const NUDGE_GIVE_UP_MS = 5 * 60000

  // ---------- Sites ----------
  const big = (v) => {
    const r = v.getBoundingClientRect()
    return r.width >= 200 && r.height >= 100
  }
  const cleanDocTitle = (t, patterns) => {
    let s = (t || '').trim()
    for (const p of patterns) s = s.replace(p, '').trim()
    return s || null
  }
  const text = (sel, root = document) => {
    const el = root.querySelector(sel)
    const s = el && el.textContent ? el.textContent.replace(/\s+/g, ' ').trim() : ''
    return s || null
  }

  const SITES = [
    {
      service: 'NETFLIX',
      hosts: /(^|\.)netflix\.com$/,
      isWatchPage: (path) => path.startsWith('/watch/'),
      // Setting video.currentTime makes Netflix's player fail with error M7375, and seeking through its
      // player object would be a private API. So the extension never seeks here: it reports
      // canSeek: false and moves with playbackRate (or asks the person to seek).
      seekBlocked: true,
      itemId: (path) => /^\/watch\/(\d+)/.exec(path)?.[1] ?? null,
      accepts: big,
      readTitle() {
        // The player's title block, shown while the controls are visible:
        // <div data-uia="video-title"><h4>Show</h4><span>E3</span><span>Episode name</span></div>
        const box = document.querySelector('[data-uia="video-title"]')
        if (!box) return null
        const h4 = box.querySelector('h4')
        if (!h4) return { title: text('[data-uia="video-title"]'), subtitle: null }
        const parts = [...box.querySelectorAll('span')].map((s) => s.textContent?.trim()).filter(Boolean)
        return { title: h4.textContent?.trim() || null, subtitle: parts.length ? parts.join(' · ') : null }
      },
      errorShown() {
        // Netflix's error screen (e.g. M7375 after a direct seek). Visible text, read once per check.
        const box = document.querySelector('.nf-error-wrapper, [data-uia="player-error"], .watch-video--error-wrapper')
        return !!box || /\bM7375\b/.test(document.body?.innerText?.slice(0, 5000) ?? '')
      }
    },
    {
      service: 'PRIME_VIDEO',
      hosts: /(^|\.)(primevideo\.com|amazon\.com|amazon\.in)$/,
      // The player opens as an overlay on the detail page; the detail page also autoplays trailers, so
      // only a video inside the web player counts.
      isWatchPage: () => true,
      itemId: (path, search) =>
        new URLSearchParams(search).get('gti') ?? /\/(?:detail|dp)\/([A-Za-z0-9.]{8,})/.exec(path)?.[1] ?? null,
      accepts(v) {
        if (!big(v)) return false
        const root = v.closest('.webPlayerSDKContainer, .webPlayerContainer, #dv-web-player, [class*="atvwebplayersdk"]')
        return !!root || document.fullscreenElement?.contains(v) === true
      },
      readTitle() {
        const title = text('.atvwebplayersdk-title-text')
        if (!title) return null
        return { title, subtitle: text('.atvwebplayersdk-subtitle-text') }
      }
    },
    {
      service: 'JIOHOTSTAR',
      hosts: /(^|\.)(hotstar\.com|jiohotstar\.com)$/,
      isWatchPage: (path) => /\/watch(\/|$)/.test(path),
      // …/<show id>/<episode name>/<content id>/watch: the last long number is the content id.
      itemId: (path) => path.match(/\d{6,}/g)?.at(-1) ?? null,
      accepts: big,
      readTitle() {
        const title = cleanDocTitle(document.title, [
          /^watch\s+/i,
          /\s*[-|]\s*(jio\s*hotstar|disney\+?\s*hotstar|hotstar)\s*$/i,
          /\s+(online|on\s+jio\s*hotstar)\s*$/i
        ])
        return title && !/^(jio\s*)?hotstar$/i.test(title) ? { title, subtitle: null } : null
      }
    },
    {
      service: 'YOUTUBE',
      hosts: /(^|\.)youtube\.com$/,
      isWatchPage: (path) => path === '/watch',
      itemId: (path, search) => new URLSearchParams(search).get('v'),
      accepts: (v) => big(v) && !!v.closest('#movie_player'),
      // An ad plays in the same <video>: while it shows, report buffering so the room waits instead of
      // syncing anyone to the ad's position.
      adShowing: () => !!document.querySelector('#movie_player.ad-showing'),
      readTitle() {
        const title = text('h1.ytd-watch-metadata yt-formatted-string') ?? cleanDocTitle(document.title, [/^\(\d+\)\s*/, /\s*-\s*YouTube\s*$/i])
        if (!title || /^youtube$/i.test(title)) return null
        return { title, subtitle: text('ytd-watch-metadata #owner #channel-name a') }
      }
    }
  ]

  const site = SITES.find((s) => s.hosts.test(loc().hostname))
  if (!site || !api?.runtime?.connect) return

  // ---------- State ----------
  let video = null
  let port = null
  let portRetry = null
  let lastCommandId = null
  let lastSnapshotAt = -Infinity
  let lastInfoAt = -Infinity
  let snapshotQueued = false
  let waiting = false // 'waiting'/'stalled' seen and not yet cleared by 'playing'
  let titleCache = { url: '', title: null, subtitle: null }
  let seekSupport = site.seekBlocked ? 'blocked' : 'unknown'
  let rateSupport = 'unknown'
  let pendingSeek = null
  let ourSeekUntil = 0 // seeking events before this time are ours, not the person's
  let nudge = null
  let problem = null
  let lastInfoJson = ''
  const version = api.runtime.getManifest().version
  const seekKey = `seek:${site.service}`

  // What we learned about seeking on this site, kept per extension version (a site may change).
  if (!site.seekBlocked) try {
    api.storage.local.get(seekKey).then((r) => {
      const saved = r?.[seekKey]
      if (saved && saved.version === version && (saved.state === 'ok' || saved.state === 'blocked')) {
        seekSupport = saved.state
      }
    }, () => {})
  } catch {
    /* storage unavailable: test every session */
  }

  function setSeekSupport(state) {
    if (seekSupport === state || site.seekBlocked) return
    seekSupport = state
    try {
      void api.storage.local.set({ [seekKey]: { state, version } })
    } catch {
      /* ignore */
    }
    sendInfo(true)
  }

  // ---------- Port to the background ----------
  function alive() {
    try {
      return !!api.runtime?.id // undefined once the extension was reloaded or removed
    } catch {
      return false
    }
  }

  function ensurePort() {
    if (port || !alive()) return port
    try {
      port = api.runtime.connect({ name: 'bj-player' })
    } catch {
      port = null
      return null
    }
    port.onMessage.addListener(onMessage)
    port.onDisconnect.addListener(() => {
      port = null
      // The background restarted (service worker) or the extension went away. Reconnect if we still
      // have a player; a dead extension makes alive() false and we stop.
      if (video && alive() && !portRetry) {
        portRetry = setTimeout(() => {
          portRetry = null
          if (video && ensurePort()) {
            lastInfoJson = ''
            sendInfo(true)
            sendSnapshot()
          }
        }, 500)
      }
    })
    return port
  }

  function post(msg) {
    const p = ensurePort()
    if (!p) return
    try {
      p.postMessage(msg)
    } catch {
      port = null
    }
  }

  function dropPort() {
    if (portRetry) clearTimeout(portRetry)
    portRetry = null
    if (port) {
      try {
        port.disconnect() // the background turns this into tabClosed
      } catch {
        /* already gone */
      }
    }
    port = null
    lastInfoJson = ''
  }

  // ---------- Finding the active <video> ----------
  function pickVideo() {
    let best = null
    let bestScore = -1
    for (const v of document.querySelectorAll('video')) {
      if (!v.isConnected || !(v.currentSrc || v.src || v.srcObject)) continue
      if (!site.accepts(v)) continue
      const r = v.getBoundingClientRect()
      let score = r.width * r.height
      if (!v.paused) score *= 2
      if (Number.isFinite(v.duration) && v.duration > 60) score *= 1.5
      if (score > bestScore) {
        best = v
        bestScore = score
      }
    }
    return best
  }

  const EVENTS_NOW = ['play', 'pause', 'playing', 'seeking', 'seeked', 'waiting', 'stalled', 'ratechange', 'ended', 'loadedmetadata', 'durationchange']
  const EVENTS_ALL = [...EVENTS_NOW, 'canplay', 'error']

  function onVideoEvent(e) {
    const t = e.type
    if (t === 'waiting' || t === 'stalled') waiting = true
    if (t === 'playing' || t === 'canplay' || t === 'pause' || t === 'seeked') waiting = false
    if (t === 'seeking' && performance.now() > ourSeekUntil) cancelNudge() // the person scrubbed
    if (t === 'pause' && nudge && performance.now() > ourSeekUntil) cancelNudge()
    if (t === 'playing' && problem?.code === 'play-blocked') setProblem(null)
    if (t === 'error' && pendingSeek) verifySeek(pendingSeek)
    if (t === 'play' || t === 'pause') sendInfo(false)
    if (EVENTS_NOW.includes(t)) queueSnapshot()
  }

  function bind(v) {
    if (video === v) return
    if (video) for (const t of EVENTS_ALL) video.removeEventListener(t, onVideoEvent)
    cancelNudge()
    video = v
    waiting = false
    if (!video) {
      overlay.refresh()
      dropPort()
      return
    }
    for (const t of EVENTS_ALL) video.addEventListener(t, onVideoEvent)
    overlay.refresh()
    ensurePort()
    sendInfo(true)
    queueSnapshot()
  }

  // ---------- Snapshots ----------
  function snapshot() {
    const v = video
    const info = readTitle()
    const d = v.duration
    const l = loc()
    let itemId = null
    try {
      itemId = site.itemId?.(l.pathname, l.search ?? '') ?? null
    } catch {
      itemId = null
    }
    const s = {
      service: site.service,
      title: info.title,
      subtitle: info.subtitle,
      durationMs: Number.isFinite(d) && d > 0 ? Math.round(d * 1000) : null,
      // -1 = not known yet (HAVE_METADATA = 1: before that currentTime is a placeholder 0).
      positionMs: v.readyState >= 1 && Number.isFinite(v.currentTime) ? Math.max(0, Math.round(v.currentTime * 1000)) : -1,
      playing: !v.paused && !v.ended,
      // HAVE_FUTURE_DATA = 3: below that the player can't move forward.
      buffering: !v.ended && (waiting || v.seeking || v.readyState < 3 || site.adShowing?.() === true),
      playbackRate: v.playbackRate,
      sampledAt: performance.now(),
      ageMs: 0,
      itemId
    }
    if (seekSupport !== 'unknown') s.canSeek = seekSupport === 'ok'
    if (rateSupport !== 'unknown') s.canSetRate = rateSupport === 'ok'
    return s
  }

  function sendSnapshot() {
    if (!video) return
    const s = snapshot()
    lastSnapshotAt = s.sampledAt
    // Time between reading the element and handing the message over (≈ 0; kept honest).
    s.ageMs = performance.now() - s.sampledAt
    post({ t: 'snapshot', snapshot: s, lastCommandId })
  }

  function queueSnapshot() {
    if (snapshotQueued) return
    snapshotQueued = true
    setTimeout(() => {
      snapshotQueued = false
      sendSnapshot()
    }, 0) // several events in one task (pause + ratechange…) → one snapshot
  }

  // ---------- Title (best effort) ----------
  function readTitle() {
    const url = loc().pathname
    if (titleCache.url !== url) titleCache = { url, title: null, subtitle: null }
    let found = null
    try {
      found = site.readTitle()
    } catch {
      found = null
    }
    if (!found?.title) {
      // navigator.mediaSession is a standard web API (the same thing the Android app reads).
      try {
        const md = navigator.mediaSession?.metadata
        const t = md?.title?.trim()
        if (t && !/^(netflix|prime video|jiohotstar|hotstar)$/i.test(t)) {
          found = { title: t, subtitle: md.artist?.trim() || md.album?.trim() || null }
        }
      } catch {
        /* ignore */
      }
    }
    if (found?.title) {
      // Controls hide after a few seconds and take the title with them: keep the last one per page.
      titleCache.title = found.title.slice(0, 200)
      titleCache.subtitle = found.subtitle ? found.subtitle.slice(0, 200) : null
    }
    return { title: titleCache.title, subtitle: titleCache.subtitle }
  }

  function sendInfo(force) {
    if (!video) return
    const info = readTitle()
    const msg = {
      t: 'info',
      service: site.service,
      title: info.title,
      subtitle: info.subtitle,
      playing: !video.paused && !video.ended,
      seek: seekSupport,
      problem
    }
    const json = JSON.stringify(msg)
    if (!force && json === lastInfoJson) return
    lastInfoJson = json
    post(msg)
  }

  function setProblem(p) {
    if (JSON.stringify(p) === JSON.stringify(problem)) return
    problem = p
    sendInfo(true)
  }

  // ---------- Commands ----------
  function onMessage(msg) {
    if (!msg) return
    if (msg.t === 'overlay') {
      overlay.set(msg.overlay ?? null)
      return
    }
    if (msg.t === 'navigate') {
      navigateTo(msg.url)
      return
    }
    if (!video) return
    if (msg.t === 'command') {
      applyCommand(msg.command)
      lastCommandId = msg.commandId ?? lastCommandId
      queueSnapshot()
    } else if (msg.t === 'local') {
      // Popup's "Pause for all" / "Play for all": a local action like a click in the player, so the app
      // sees a change it didn't ask for and sends it to the room. lastCommandId stays as it was.
      if (msg.action === 'pause') video.pause()
      else if (msg.action === 'play') playVideo()
    }
  }

  function applyCommand(c) {
    if (!c || typeof c.type !== 'string') return
    if (c.type !== 'seek') cancelNudge()
    switch (c.type) {
      case 'play':
        playVideo()
        break
      case 'pause':
        video.pause()
        break
      case 'rate': {
        const r = Number(c.playbackRate)
        if (Number.isFinite(r)) setRate(Math.min(2, Math.max(0.5, r)))
        break
      }
      case 'seek': {
        const target = Number(c.positionMs)
        if (Number.isFinite(target) && target >= 0) seekTo(target)
        break
      }
    }
  }

  function playVideo() {
    try {
      const p = video.play()
      if (p && typeof p.catch === 'function') {
        p.catch((err) => {
          // NotAllowedError: the browser wants a click on the page first (autoplay policy).
          if (err && err.name === 'NotAllowedError') setProblem({ code: 'play-blocked' })
        })
      }
    } catch {
      /* ignore */
    }
  }

  // ---------- Seeking, with detection and the speed fallback ----------
  function seekTo(targetMs) {
    cancelNudge()
    const v = video
    const gap = targetMs - v.currentTime * 1000
    if (seekSupport === 'blocked') {
      nudgeTo(targetMs)
      return
    }
    const now = performance.now()
    ourSeekUntil = now + SEEK_VERIFY_MS
    const p = { v, targetMs, startedAt: now, wasPlaying: !v.paused, test: Math.abs(gap) >= SEEK_SKIP_TEST_MS, done: false }
    pendingSeek = p
    try {
      v.currentTime = targetMs / 1000
    } catch {
      p.done = true
      pendingSeek = null
      setSeekSupport('blocked')
      nudgeTo(targetMs)
      return
    }
    v.addEventListener('seeked', () => setTimeout(() => verifySeek(p), 300), { once: true })
    setTimeout(() => verifySeek(p), SEEK_VERIFY_MS)
  }

  function verifySeek(p) {
    if (p.done) return
    p.done = true
    if (pendingSeek === p) pendingSeek = null
    const elapsed = performance.now() - p.startedAt
    const current = pickVideo()
    const crashed = !!p.v.error || !p.v.isConnected || (current !== null && current !== p.v) || !!site.errorShown?.()
    if (crashed) {
      setSeekSupport('blocked')
      setProblem({ code: 'player-error' })
      return
    }
    const expected = p.targetMs + (p.wasPlaying && !p.v.paused ? elapsed : 0)
    const landed = Math.abs(p.v.currentTime * 1000 - expected) <= SEEK_TOLERANCE_MS
    if (landed) {
      if (p.test) setSeekSupport('ok')
      return
    }
    if (p.v.seeking) return // still loading at the new spot: no verdict
    setSeekSupport('blocked')
    nudgeTo(p.targetMs + (p.wasPlaying ? elapsed : 0))
  }

  function nudgeTo(targetMs) {
    cancelNudge()
    const v = video
    if (!v) return
    const gap = targetMs - v.currentTime * 1000
    if (Math.abs(gap) <= NUDGE_DONE_MS) return
    if (v.paused || Math.abs(gap) > NUDGE_LIMIT_MS) {
      // Speed can't move a paused video, and big gaps would take minutes: ask the person.
      setProblem({ code: 'seek-blocked', atMs: Math.round(targetMs) })
      return
    }
    // The room keeps playing while we catch up: the goal moves forward in real time.
    const n = { targetMs, t0: performance.now(), timer: 0, lastRate: v.playbackRate }
    nudge = n
    n.timer = setInterval(() => {
      const vv = video
      const elapsed = performance.now() - n.t0
      if (nudge !== n || !vv || vv.paused || elapsed > NUDGE_GIVE_UP_MS) {
        cancelNudge()
        return
      }
      const want = n.targetMs + elapsed
      const d = want - vv.currentTime * 1000
      if (Math.abs(d) <= NUDGE_DONE_MS) {
        cancelNudge()
        if (problem?.code === 'seek-blocked') setProblem(null)
        return
      }
      const delta = Math.max(-NUDGE_MAX_RATE_DELTA, Math.min(NUDGE_MAX_RATE_DELTA, d / NUDGE_GAIN_MS))
      const rate = Math.round((1 + delta) * 100) / 100
      if (Math.abs(rate - vv.playbackRate) >= 0.005) setRate(rate)
    }, NUDGE_TICK_MS)
  }

  /** Sets playbackRate and checks a second later that the page didn't put it back. */
  function setRate(rate) {
    const v = video
    if (!v) return
    v.playbackRate = rate
    setTimeout(() => {
      if (video !== v || v.playbackRate === rate) {
        if (video === v && rateSupport !== 'ok') {
          rateSupport = 'ok'
          queueSnapshot()
        }
        return
      }
      // Changed by us again in the meantime? Then this check is stale.
      if (nudge || Math.abs(v.playbackRate - 1) > 0.001) return
      if (rateSupport !== 'blocked') {
        rateSupport = 'blocked'
        queueSnapshot()
      }
    }, RATE_VERIFY_MS)
  }

  function cancelNudge() {
    if (!nudge) return
    clearInterval(nudge.timer)
    nudge = null
    if (video && video.playbackRate !== 1) video.playbackRate = 1
  }

  // ---------- Opening a title (the host's exact title / next episode) ----------
  function navigateTo(url) {
    let u
    try {
      u = new URL(url)
    } catch {
      return
    }
    // Only this service's own pages, over https.
    if (u.protocol !== 'https:' || !site.hosts.test(u.hostname)) return
    if (u.href === loc().href) return
    if (!TEST) location.assign(u.href)
  }

  // ---------- Overlay: room chat and reactions over the video ----------
  // A closed shadow root keeps the page's CSS out and ours in. It's re-parented into the fullscreen
  // element when the player goes fullscreen (anything outside it isn't drawn then).
  const overlay = (() => {
    let state = null
    let host = null
    let root = null
    let open = false // the message box
    let hidden = false // the person closed the overlay for this page
    const shownReactions = new Set()
    let firstReactions = true

    const CSS = `
      :host { all: initial; }
      .wrap { position: fixed; right: 20px; bottom: 96px; width: 300px; z-index: 2147483647; font: 14px/1.35 Inter, 'Segoe UI', system-ui, sans-serif; color: #efe9de; pointer-events: none; display: flex; flex-direction: column; gap: 6px; align-items: flex-end; }
      .msgs { display: flex; flex-direction: column; gap: 4px; align-items: flex-end; max-width: 100%; }
      .msg { background: rgba(18,17,16,0.78); padding: 6px 10px; border-radius: 12px; max-width: 100%; word-break: break-word; animation: in .25s ease-out; transition: opacity .6s; }
      .msg b { color: #ff7a45; font-weight: 600; margin-right: 4px; }
      .msg.me { background: rgba(200,16,46,0.82); }
      .msg.old { opacity: 0; }
      .bar { pointer-events: auto; display: flex; gap: 4px; align-items: center; background: rgba(18,17,16,0.82); border-radius: 999px; padding: 4px; opacity: .35; transition: opacity .2s; }
      .bar:hover, .bar.open { opacity: 1; }
      button { all: unset; cursor: pointer; width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; font-size: 17px; }
      button:hover { background: rgba(239,233,222,0.14); }
      input { all: unset; width: 170px; padding: 0 10px; height: 30px; color: #efe9de; font-size: 13px; }
      input::placeholder { color: rgba(239,233,222,0.6); }
      .float { position: fixed; right: 60px; bottom: 140px; font-size: 34px; z-index: 2147483647; pointer-events: none; animation: rise 2.6s ease-out forwards; }
      @keyframes rise { from { transform: translateY(0) scale(.8); opacity: 1; } to { transform: translateY(-260px) scale(1.25); opacity: 0; } }
      @keyframes in { from { transform: translateY(6px); opacity: 0; } to { transform: none; opacity: 1; } }
      @media (prefers-reduced-motion: reduce) { .float, .msg { animation: none; } }
    `

    function ensure() {
      if (host) return
      host = document.createElement('bingejam-overlay')
      root = host.attachShadow({ mode: 'closed' })
      const style = document.createElement('style')
      style.textContent = CSS
      root.appendChild(style)
      const wrap = document.createElement('div')
      wrap.className = 'wrap'
      wrap.innerHTML = '<div class="msgs"></div><div class="bar"></div>'
      root.appendChild(wrap)
      // Typing must not reach the player's shortcuts (space = pause, f = fullscreen…).
      for (const t of ['keydown', 'keyup', 'keypress']) wrap.addEventListener(t, (e) => e.stopPropagation())
      document.addEventListener('fullscreenchange', place)
      place()
    }

    function place() {
      if (!host) return
      const parent = document.fullscreenElement ?? document.documentElement
      if (host.parentNode !== parent) parent.appendChild(host)
    }

    function remove() {
      if (!host) return
      document.removeEventListener('fullscreenchange', place)
      host.remove()
      host = null
      root = null
    }

    function render() {
      if (!state || hidden || !video) {
        remove()
        return
      }
      ensure()
      place()
      const msgs = root.querySelector('.msgs')
      msgs.textContent = ''
      for (const m of state.chat.slice(-5)) {
        const el = document.createElement('div')
        el.className = m.fromMe ? 'msg me' : 'msg'
        if (!m.fromMe) {
          const b = document.createElement('b')
          b.textContent = m.name
          el.appendChild(b)
        }
        el.appendChild(document.createTextNode(m.text))
        msgs.appendChild(el)
        // Fade after a while unless the box is open.
        setTimeout(() => {
          if (!open) el.classList.add('old')
        }, 9000)
      }
      const bar = root.querySelector('.bar')
      bar.textContent = ''
      bar.classList.toggle('open', open)
      for (const e of state.palette) {
        const b = document.createElement('button')
        b.textContent = e
        b.title = `React ${e}`
        b.addEventListener('click', () => post({ t: 'overlayReact', emoji: e }))
        bar.appendChild(b)
      }
      if (open) {
        const input = document.createElement('input')
        input.placeholder = 'Message the room'
        input.maxLength = 500
        input.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' && input.value.trim()) {
            post({ t: 'overlaySend', text: input.value.trim() })
            input.value = ''
          } else if (e.key === 'Escape') {
            open = false
            render()
          }
        })
        bar.appendChild(input)
        setTimeout(() => input.focus(), 0)
      }
      const chat = document.createElement('button')
      chat.textContent = open ? '✕' : '💬'
      chat.title = open ? 'Close' : 'Message the room'
      chat.addEventListener('click', () => {
        open = !open
        render()
      })
      bar.appendChild(chat)
      const hide = document.createElement('button')
      hide.textContent = '–'
      hide.title = 'Hide BingeJam on this page'
      hide.addEventListener('click', () => {
        hidden = true
        render()
      })
      bar.appendChild(hide)
    }

    function floatReactions() {
      if (!state || hidden) return
      for (const r of state.reactions) {
        if (shownReactions.has(r.id)) continue
        shownReactions.add(r.id)
        // The first batch is history: don't replay it.
        if (firstReactions || !root) continue
        const el = document.createElement('div')
        el.className = 'float'
        el.textContent = r.emoji
        el.style.right = `${40 + Math.round(Math.random() * 60)}px`
        root.appendChild(el)
        setTimeout(() => el.remove(), 2700)
      }
      firstReactions = false
    }

    return {
      set(next) {
        const was = state
        state = next && Array.isArray(next.chat) && Array.isArray(next.reactions) ? next : null
        if (!state || (was && was.code !== state.code)) {
          shownReactions.clear()
          firstReactions = true
          hidden = false
        }
        render()
        floatReactions()
      },
      /** The video came or went. */
      refresh: render
    }
  })()

  // ---------- Main loop ----------
  function tick() {
    if (!alive()) {
      clearInterval(loop)
      bind(null)
      return
    }
    if (!site.isWatchPage(loc().pathname)) {
      bind(null)
      return
    }
    const v = pickVideo()
    if (v !== video) bind(v)
    if (!video) return
    const now = performance.now()
    if (now - lastSnapshotAt >= HEARTBEAT_MS - TICK_MS / 2) sendSnapshot()
    if (now - lastInfoAt >= INFO_MS) {
      lastInfoAt = now
      sendInfo(false)
    }
  }

  const loop = setInterval(tick, TICK_MS)
  tick()
})()
