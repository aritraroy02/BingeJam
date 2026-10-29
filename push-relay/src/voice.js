// Voice chat in rooms: our own relay, one Durable Object per room (free plan: SQLite-backed DOs).
//
// The phone records its mic, compresses it with Opus and sends it here over a WebSocket; we pass
// each message on to everyone else in the room's voice chat. No audio is stored or decoded here.
// Hibernatable WebSockets: an idle room costs nothing, and only incoming messages count towards
// the free plan's 100k requests/day (20 messages = 1 request). Phones send only while someone is
// actually talking, 40 ms of audio per message.
//
// GET /voice/<ROOM CODE>  (WebSocket, "Authorization: Bearer <Firebase ID token>"): only members
//                         of rooms/<code>/members may join. 429 when the room's voice chat is full.
// GET /voice/echo         the same, but alone and your own audio comes back ("hear yourself").
//
// Wire format. Binary, phone → relay:  [1][seq u16][flags u8][count u8] then count × [len u16][opus]
//                     relay → phones:  [1][slot u8][seq u16][flags u8][count u8] … (slot inserted)
//   flags bit 0: sent in HD. All numbers big-endian.
// Text (JSON), phone → relay: {"t":"state","muted":bool,"deafened":bool,"quality":"hd"|"sd"}
//              relay → phones: {"t":"hello","slot":n,"peers":[peer…]}  (to the one who joined)
//                              {"t":"join","peer":peer} {"t":"leave","slot":n}
//                              {"t":"state","slot":n,"muted":bool,"deafened":bool,"quality":"hd"|"sd"}
//   peer = {"slot","uid","name","muted","deafened","quality"}

const ROOM_CODE = /^[A-HJ-NP-Z2-9]{6}$/;
const MAX_PEERS = 8;
const MAX_AUDIO_BYTES = 2048;
const AUDIO = 1;

/** Worker side: checks who is asking and hands the WebSocket to the room's Durable Object. */
export async function connectVoice(request, env, target, { verifyIdToken, bearer, Database, clip }) {
  if (request.headers.get('Upgrade') !== 'websocket') return new Response('Expected a WebSocket', { status: 426 });
  let uid;
  try {
    uid = await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }

  let roomName;
  let name;
  let echo = false;
  if (target === 'echo') {
    roomName = `echo:${uid}`;
    name = 'You';
    echo = true;
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

  const headers = new Headers({ Upgrade: 'websocket', 'X-Voice-Uid': uid, 'X-Voice-Name': encodeURIComponent(name) });
  if (echo) headers.set('X-Voice-Echo', '1');
  const stub = env.VOICE.get(env.VOICE.idFromName(roomName), { locationHint: 'apac' });
  return stub.fetch(new Request('https://voice/connect', { headers }));
}

export class VoiceRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
  }

  async fetch(request) {
    const uid = request.headers.get('X-Voice-Uid');
    const name = decodeURIComponent(request.headers.get('X-Voice-Name') || 'Friend');
    const echo = request.headers.get('X-Voice-Echo') === '1';
    if (!uid) return new Response('Bad request', { status: 400 });

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
      if (others.length >= MAX_PEERS) return new Response('Voice chat is full', { status: 429 });
      const used = new Set(others.map((p) => p.slot));
      slot = 0;
      while (used.has(slot)) slot++;
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    const me = { uid, name, slot, muted: false, deafened: false, quality: 'hd', echo };
    server.serializeAttachment(me);
    server.send(JSON.stringify({ t: 'hello', slot, peers: others.map(info) }));
    this.broadcast(server, JSON.stringify({ t: 'join', peer: info(me) }));
    return new Response(null, { status: 101, webSocket: client });
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
      if (m.t !== 'state') return;
      me.muted = m.muted === true;
      me.deafened = m.deafened === true;
      me.quality = m.quality === 'sd' ? 'sd' : 'hd';
      ws.serializeAttachment(me);
      this.broadcast(ws, JSON.stringify({ t: 'state', slot: me.slot, muted: me.muted, deafened: me.deafened, quality: me.quality }));
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
  return { slot: a.slot, uid: a.uid, name: a.name, muted: a.muted, deafened: a.deafened === true, quality: a.quality };
}
