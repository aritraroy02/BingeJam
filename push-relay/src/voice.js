// Voice chat in rooms and in groups' voice channels: our own relay, one Durable Object per room or
// channel (free plan: SQLite-backed DOs).
//
// The phone (or PC) records its mic, compresses it with Opus and sends it here over a WebSocket; we
// pass each message on to everyone else in that voice chat. No audio is stored or decoded here.
// Hibernatable WebSockets: an idle room costs nothing, and only incoming messages count towards
// the free plan's 100k requests/day (20 messages = 1 request). Apps send only while someone is
// actually talking, 40 ms of audio per message.
//
// GET /voice/<ROOM CODE>  (WebSocket): only members of rooms/<code>/members may join. 429 when the
//                         room's voice chat is full.
// GET /voice/g/<gid>/<cid> a group's voice channel (PC app, 2026-11-22): members of groups/<gid>;
//                         <cid> is "lounge" (every group's built-in one) or a channel in
//                         groups/<gid>/channels with kind "voice".
// GET /voice/echo         the same, but alone and your own audio comes back ("hear yourself").
//
// Sign-in: "Authorization: Bearer <Firebase ID token>" (phones), or, since a browser WebSocket can't
// set headers, the subprotocols ["bingejam-voice", <ID token>] (PC app); we answer "bingejam-voice".
//
// Wire format. Binary, app → relay:  [1][seq u16][flags u8][count u8] then count × [len u16][opus]
//                     relay → apps:  [1][slot u8][seq u16][flags u8][count u8] … (slot inserted)
//   flags bit 0: sent in HD. All numbers big-endian.
// Text (JSON), app → relay: {"t":"state","muted":bool,"deafened":bool,"quality":"hd"|"sd"}
//              relay → apps: {"t":"hello","slot":n,"peers":[peer…],"server":"BOM"|null,"edge":"BOM"|null}
//                              (to the one who joined; server = the data centre this voice chat
//                              runs in, edge = the one the app connected through; IATA codes)
//                            {"t":"join","peer":peer} {"t":"leave","slot":n}
//                            {"t":"state","slot":n,"muted":bool,"deafened":bool,"quality":"hd"|"sd","live":bool}
//   peer = {"slot","uid","name","muted","deafened","quality","live"}
//   live (PC screen share, 2026-11-22): that person is sharing their screen; phones leave it out (false).
// Screen share set-up, app → relay: {"t":"signal","to":slot,"data":{…}} → to that slot only:
//   {"t":"signal","from":slot,"data":{…}} (WebRTC offer/answer/ICE; the video goes PC to PC, see GET /ice).
// Text "ping" → "pong": answered by Cloudflare without waking the Durable Object (latency check).

const ROOM_CODE = /^[A-HJ-NP-Z2-9]{6}$/;
const GROUP_ID = /^[A-Za-z0-9_-]{1,40}$/;
/** Every group has this voice channel without it being in groups/<gid>/channels. */
export const LOUNGE = 'lounge';
const MAX_PEERS = 8;
const MAX_CHANNEL_PEERS = 16;
const MAX_AUDIO_BYTES = 2048;
/** A WebRTC offer with every codec is ~10 KB; anything much bigger isn't one. */
const MAX_SIGNAL_CHARS = 32768;
const AUDIO = 1;
const PROTOCOL = 'bingejam-voice';

/** The ID token from "Sec-WebSocket-Protocol: bingejam-voice, <token>" (browsers), else null. */
export function protocolToken(request) {
  const offered = (request.headers.get('Sec-WebSocket-Protocol') || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (!offered.includes(PROTOCOL)) return null;
  return offered.find((p) => p !== PROTOCOL) || null;
}

/** Worker side: checks who is asking and hands the WebSocket to the room's Durable Object. */
export async function connectVoice(request, env, target, { verifyIdToken, bearer, Database, clip }) {
  if (request.headers.get('Upgrade') !== 'websocket') return new Response('Expected a WebSocket', { status: 426 });
  const viaProtocol = protocolToken(request);
  let uid;
  try {
    uid = await verifyIdToken(bearer(request) || viaProtocol, env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }

  let roomName;
  let name;
  let echo = false;
  let max = MAX_PEERS;
  const parts = target.split('/');
  if (target === 'echo') {
    roomName = `echo:${uid}`;
    name = 'You';
    echo = true;
  } else if (parts[0] === 'g' && parts.length === 3) {
    const [, gid, cid] = parts;
    if (!GROUP_ID.test(gid) || !GROUP_ID.test(cid)) return new Response('Bad request', { status: 400 });
    const db = new Database(env);
    let member;
    let kind;
    let profileName;
    try {
      [member, kind, profileName] = await Promise.all([
        db.get(`groups/${gid}/members/${uid}`),
        cid === LOUNGE ? Promise.resolve('voice') : db.get(`groups/${gid}/channels/${cid}/kind`),
        db.get(`users/${uid}/displayName`),
      ]);
    } catch (e) {
      return new Response('Relay error', { status: 502 });
    }
    if (!member) return new Response('Not in this group', { status: 403 });
    if (kind !== 'voice') return new Response('No such voice channel', { status: 404 });
    name = clip(String(profileName || 'Friend'), 40);
    roomName = `group:${gid}:${cid}`;
    max = MAX_CHANNEL_PEERS;
  } else {
    if (!ROOM_CODE.test(target)) return new Response('Bad request', { status: 400 });
    let member;
    try {
      member = await new Database(env).get(`rooms/${target}/members/${uid}`);
    } catch (e) {
      return new Response('Relay error', { status: 502 });
    }
    if (!member) return new Response('Not in this room', { status: 403 });
    name = clip(String(member.name || 'Friend'), 40);
    roomName = `room:${target}`;
  }

  const headers = new Headers({ Upgrade: 'websocket', 'X-Voice-Uid': uid, 'X-Voice-Name': encodeURIComponent(name), 'X-Voice-Max': String(max) });
  if (echo) headers.set('X-Voice-Echo', '1');
  if (viaProtocol) headers.set('X-Voice-Protocol', PROTOCOL);
  const edge = request.cf && typeof request.cf.colo === 'string' ? request.cf.colo : '';
  if (/^[A-Z]{3}$/.test(edge)) headers.set('X-Voice-Edge', edge);
  const stub = env.VOICE.get(env.VOICE.idFromName(roomName), { locationHint: 'apac' });
  return stub.fetch(new Request('https://voice/connect', { headers }));
}

export class VoiceRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
    /** IATA code of the data centre we run in, once looked up (see serverColo). */
    this.colo = undefined;
    // Latency checks are answered without waking us (and without counting as requests).
    if (typeof WebSocketRequestResponsePair === 'function' && ctx.setWebSocketAutoResponse) {
      ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    }
  }

  /**
   * Where this Durable Object runs: Cloudflare's trace page answers with the data centre that made
   * the request, which for a fetch from here is our own. Null if it can't be told.
   */
  async serverColo() {
    if (this.colo !== undefined) return this.colo;
    try {
      const res = await fetch('https://www.cloudflare.com/cdn-cgi/trace', { signal: AbortSignal.timeout(1500) });
      const code = /^colo=([A-Z]{3})$/m.exec(await res.text())?.[1] ?? null;
      this.colo = code;
    } catch (e) {
      this.colo = null;
    }
    return this.colo;
  }

  async fetch(request) {
    const uid = request.headers.get('X-Voice-Uid');
    const name = decodeURIComponent(request.headers.get('X-Voice-Name') || 'Friend');
    const echo = request.headers.get('X-Voice-Echo') === '1';
    const maxPeers = Number(request.headers.get('X-Voice-Max')) || MAX_PEERS;
    const protocol = request.headers.get('X-Voice-Protocol');
    const edge = request.headers.get('X-Voice-Edge');
    if (!uid) return new Response('Bad request', { status: 400 });
    const server = await this.serverColo();

    // A reconnect from the same person replaces their old socket and keeps their slot, so the
    // others' decoders carry on.
    let slot = -1;
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a && !a.replaced && a.uid === uid) {
        slot = a.slot;
        ws.serializeAttachment({ ...a, replaced: true });
        try {
          ws.close(4000, 'replaced');
        } catch (e) {}
      }
    }
    const others = this.peers();
    if (slot < 0) {
      if (others.length >= maxPeers) return new Response('Voice chat is full', { status: 429 });
      const used = new Set(others.map((p) => p.slot));
      slot = 0;
      while (used.has(slot)) slot++;
    }

    const pair = new WebSocketPair();
    const [client, socket] = Object.values(pair);
    this.ctx.acceptWebSocket(socket);
    const me = { uid, name, slot, muted: false, deafened: false, quality: 'hd', echo };
    socket.serializeAttachment(me);
    socket.send(JSON.stringify({ t: 'hello', slot, peers: others.map(info), server, edge: edge || null }));
    this.broadcast(socket, JSON.stringify({ t: 'join', peer: info(me) }));
    return new Response(null, { status: 101, webSocket: client, headers: protocol ? { 'Sec-WebSocket-Protocol': protocol } : {} });
  }

  webSocketMessage(ws, message) {
    const me = ws.deserializeAttachment();
    if (!me || me.replaced) return;
    if (typeof message === 'string') {
      let m;
      try {
        m = JSON.parse(message);
      } catch (e) {
        return;
      }
      if (m.t === 'signal') {
        this.signal(me, m, message.length);
        return;
      }
      if (m.t !== 'state') return;
      me.muted = m.muted === true;
      me.deafened = m.deafened === true;
      me.quality = m.quality === 'sd' ? 'sd' : 'hd';
      me.live = m.live === true;
      ws.serializeAttachment(me);
      this.broadcast(ws, JSON.stringify({ t: 'state', slot: me.slot, muted: me.muted, deafened: me.deafened, quality: me.quality, live: me.live }));
      return;
    }
    const data = new Uint8Array(message);
    if (data.length < 5 || data.length > MAX_AUDIO_BYTES || data[0] !== AUDIO || me.muted) return;
    const out = new Uint8Array(data.length + 1);
    out[0] = AUDIO;
    out[1] = me.slot;
    out.set(data.subarray(1), 2);
    if (me.echo) {
      ws.send(out);
      return;
    }
    this.broadcast(ws, out);
  }

  webSocketClose(ws, code) {
    this.leave(ws);
    try {
      ws.close(1000, 'bye');
    } catch (e) {}
  }

  webSocketError(ws) {
    this.leave(ws);
  }

  leave(ws) {
    const me = ws.deserializeAttachment();
    if (!me || me.replaced || me.left) return;
    ws.serializeAttachment({ ...me, left: true });
    this.broadcast(ws, JSON.stringify({ t: 'leave', slot: me.slot }));
  }

  /**
   * Screen share set-up (PC app): one person's WebRTC offer / answer / ICE candidate passed to one
   * other person in the same voice chat, with the sender's slot added. Video itself never comes here.
   */
  signal(me, m, size) {
    if (me.echo || size > MAX_SIGNAL_CHARS || !Number.isInteger(m.to) || m.to === me.slot || !m.data || typeof m.data !== 'object') return;
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (!a || a.replaced || a.left || a.slot !== m.to) continue;
      try {
        ws.send(JSON.stringify({ t: 'signal', from: me.slot, data: m.data }));
      } catch (e) {}
      return;
    }
  }

  /** Everyone still connected (attachments). */
  peers() {
    return this.ctx.getWebSockets().map((ws) => ws.deserializeAttachment()).filter((a) => a && !a.replaced && !a.left);
  }

  broadcast(from, message) {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === from) continue;
      const a = ws.deserializeAttachment();
      if (!a || a.replaced || a.left) continue;
      try {
        ws.send(message);
      } catch (e) {}
    }
  }
}

function info(a) {
  return { slot: a.slot, uid: a.uid, name: a.name, muted: a.muted, deafened: a.deafened === true, quality: a.quality, live: a.live === true };
}

// ---- ICE servers for screen share (GET /ice, signed in) ----
//
// STUN (free, public) finds each PC's address; most home connections then link directly. TURN relays
// the video when they can't (strict NAT, some mobile hotspots): Cloudflare's TURN service, free up to
// its monthly allowance, used only once the TURN_KEY_ID and TURN_KEY_API_TOKEN secrets are set
// (Cloudflare dashboard → Realtime → TURN Server → create a key). Credentials last 24 h.

const STUN = { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] };

export async function iceServers(request, env, { verifyIdToken, bearer, json }) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  const servers = [STUN];
  if (env.TURN_KEY_ID && env.TURN_KEY_API_TOKEN) {
    try {
      const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ ttl: 86400 }),
      });
      if (res.ok) {
        const ice = (await res.json()).iceServers;
        for (const s of Array.isArray(ice) ? ice : [ice]) if (s && s.urls) servers.push(s);
      } else console.warn('TURN credentials failed', res.status);
    } catch (e) {
      console.warn('TURN credentials failed', e && e.message);
    }
  }
  return json({ iceServers: servers });
}
