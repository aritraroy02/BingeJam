// Push relay + profile-picture store for Vyro (Cloudflare Worker, free tier).
// Routes: POST / (notify, below), PUT|DELETE /avatar and GET /avatar/<uid> (further down).
//
// Firebase Cloud Messaging is free, but sending needs a trusted server, and Cloud Functions need
// the paid Blaze plan. This Worker is that server:
//   1. The app sends POST {type, to, messageId?} with "Authorization: Bearer <Firebase ID token>"
//      right after writing a direct message or a friend request.
//   2. We verify the ID token (signed in, not anonymous), then read the message/request from the
//      Realtime Database ourselves. The notification text never comes from the request, so a
//      client can't make us send arbitrary text, and only to someone it actually wrote to.
//   3. We send it to every token in pushTokens/{to} through FCM HTTP v1, and drop dead tokens.
//
// Secret FIREBASE_SERVICE_ACCOUNT: the Firebase service account JSON (Admin SDK key). It gives
// admin access to the database and FCM, so it lives only in Cloudflare's secret store.

const SCOPES = [
  'https://www.googleapis.com/auth/firebase.database',
  'https://www.googleapis.com/auth/userinfo.email',
  'https://www.googleapis.com/auth/firebase.messaging',
];
const JWKS_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
const UID = /^[A-Za-z0-9]{1,128}$/;
const PUSH_ID = /^[-_A-Za-z0-9]{1,40}$/;
/** Only items written in the last few minutes get a notification (no replaying old ones). */
const MAX_AGE_MS = 5 * 60_000;

let jwksCache = null; // { keys, expiresAt }
let accessCache = null; // { token, expiresAt }

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path.startsWith('/avatar/') && request.method === 'GET') return serveAvatar(path, env);
    if (path === '/avatar' && (request.method === 'PUT' || request.method === 'DELETE')) return changeAvatar(request, env);
    if (path !== '/' || request.method !== 'POST') return new Response('Not found', { status: 404 });
    return notify(request, env);
  },
};

async function notify(request, env) {
    let sender;
    try {
      sender = await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
    } catch (e) {
      return new Response('Unauthorized', { status: 401 });
    }

    let body;
    try {
      body = await request.json();
    } catch (e) {
      return new Response('Bad request', { status: 400 });
    }
    const to = String(body.to ?? '');
    if (!UID.test(to) || to === sender) return new Response('Bad request', { status: 400 });

    try {
      const db = new Database(env);
      let note = null;
      if (body.type === 'dm') note = await directMessage(db, sender, to, String(body.messageId ?? ''));
      else if (body.type === 'friend_request') note = await friendRequest(db, sender, to);
      else return new Response('Bad request', { status: 400 });

      if (note && !(await db.get(`blocked/${to}/${sender}`))) await sendToAll(db, env, to, note);
      return new Response(null, { status: 204 });
    } catch (e) {
      console.error('relay failed', e && e.message);
      return new Response('Relay error', { status: 502 });
    }
}

// ---- Profile pictures (Workers KV, free plan) ----
//
// PUT /avatar     body = image bytes, Authorization: Bearer <Firebase ID token>
//                 → {"url": "<origin>/avatar/<uid>?v=<version>"}; the app then saves that URL in
//                 users/{uid}/photoUrl (the database rules only accept this prefix for your own uid).
// DELETE /avatar  removes your picture.
// GET /avatar/<uid> serves it (public, like any profile photo). The URL changes with every upload,
// so it can be cached forever.

const MAX_GIF_BYTES = 1_048_576;
const MAX_STILL_BYTES = 300_000;
/** One upload per user per this long, so the free plan's 1,000 writes/day can't be burnt by one person. */
const UPLOAD_COOLDOWN_MS = 20_000;

async function changeAvatar(request, env) {
  let uid;
  try {
    uid = await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  const key = `avatar:${uid}`;

  if (request.method === 'DELETE') {
    await env.AVATARS.delete(key);
    return new Response(null, { status: 204 });
  }

  const declared = Number(request.headers.get('Content-Length') || 0);
  if (declared > MAX_GIF_BYTES) return json({ error: 'too_large' }, 413);
  const bytes = new Uint8Array(await request.arrayBuffer());
  const type = imageType(bytes);
  if (!type) return json({ error: 'not_an_image' }, 415);
  if (bytes.length > (type === 'image/gif' ? MAX_GIF_BYTES : MAX_STILL_BYTES)) return json({ error: 'too_large' }, 413);

  const previous = await env.AVATARS.getWithMetadata(key, { type: 'stream' });
  previous.value?.cancel();
  if (previous.metadata && Date.now() - previous.metadata.v < UPLOAD_COOLDOWN_MS) return json({ error: 'too_soon' }, 429);

  const v = Date.now();
  await env.AVATARS.put(key, bytes, { metadata: { type, v } });
  const origin = new URL(request.url).origin;
  return json({ url: `${origin}/avatar/${uid}?v=${v}` });
}

async function serveAvatar(path, env) {
  const uid = path.slice('/avatar/'.length);
  if (!UID.test(uid)) return new Response('Not found', { status: 404 });
  const { value, metadata } = await env.AVATARS.getWithMetadata(`avatar:${uid}`, { type: 'arrayBuffer' });
  if (!value) return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'public, max-age=60' } });
  return new Response(value, {
    headers: {
      'Content-Type': metadata?.type || 'application/octet-stream',
      'Cache-Control': 'public, max-age=31536000, immutable',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

/** The real type from the file's first bytes (never trust the client's Content-Type). */
function imageType(b) {
  if (b.length < 12) return null;
  if (b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38) return 'image/gif';
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg';
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png';
  if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46 &&
      b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) return 'image/webp';
  return null;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

// ---- What to send ----

async function directMessage(db, from, to, messageId) {
  if (!PUSH_ID.test(messageId)) return null;
  // Same as DmIds.dmId in the app: the two uids sorted, joined with "_".
  const dmId = from < to ? `${from}_${to}` : `${to}_${from}`;
  const msg = await db.get(`dms/${dmId}/${messageId}`);
  if (!msg || msg.from !== from || typeof msg.text !== 'string' || !isRecent(msg.sentAt)) return null;
  const profile = (await db.get(`users/${from}`)) || {};
  return {
    title: profile.displayName || 'New message',
    body: clip(msg.text, 240),
    channel: 'messages',
    data: { type: 'dm', uid: from },
  };
}

async function friendRequest(db, from, to) {
  const req = await db.get(`friendRequests/${to}/${from}`);
  if (!req || !isRecent(req.sentAt)) return null;
  const who = req.fromDisplayName ? `${req.fromDisplayName} (@${req.fromUsername})` : `@${req.fromUsername}`;
  return {
    title: 'Friend request',
    body: clip(`${who} wants to be friends`, 240),
    channel: 'friends',
    data: { type: 'friend_request', uid: from },
  };
}

async function sendToAll(db, env, to, note) {
  const tokens = await db.get(`pushTokens/${to}`, '?shallow=true');
  if (!tokens) return;
  const accessToken = await getAccessToken(env);
  await Promise.all(Object.keys(tokens).map(async (token) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: note.title, body: note.body },
          data: note.data,
          android: {
            priority: 'HIGH',
            // Same tag as the app uses, so a newer message from the same friend replaces the older one.
            notification: { channel_id: note.channel, tag: `${note.data.type}-${note.data.uid}` },
          },
        },
      }),
    });
    if (res.ok) return;
    const error = await res.json().catch(() => ({}));
    const unregistered = res.status === 404 ||
      (error.error?.details || []).some((d) => d.errorCode === 'UNREGISTERED');
    if (unregistered) await db.remove(`pushTokens/${to}/${token}`);
    else console.warn('FCM send failed', res.status, JSON.stringify(error).slice(0, 300));
  }));
}

function isRecent(sentAt) {
  return typeof sentAt === 'number' && Math.abs(Date.now() - sentAt) <= MAX_AGE_MS;
}

function clip(text, max) {
  return text.length <= max ? text : text.slice(0, max - 1) + '…';
}

// ---- Realtime Database REST (admin, via the service account's OAuth token) ----

class Database {
  constructor(env) {
    this.env = env;
    this.base = env.DATABASE_URL.replace(/\/$/, '');
  }

  url(path, query = '') {
    return `${this.base}/${path.split('/').map(encodeURIComponent).join('/')}.json${query}`;
  }

  async get(path, query) {
    const res = await fetch(this.url(path, query), {
      headers: { Authorization: `Bearer ${await getAccessToken(this.env)}` },
    });
    if (!res.ok) throw new Error(`database read ${res.status}`);
    return res.json();
  }

  async remove(path) {
    await fetch(this.url(path), {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${await getAccessToken(this.env)}` },
    });
  }
}

// ---- Firebase ID token check (who is asking) ----

function bearer(request) {
  const header = request.headers.get('Authorization') || '';
  return header.startsWith('Bearer ') ? header.slice(7).trim() : null;
}

async function verifyIdToken(token, projectId) {
  if (!token) throw new Error('missing token');
  const [h, p, s] = token.split('.');
  if (!h || !p || !s) throw new Error('malformed token');
  const header = JSON.parse(textFromB64url(h));
  const payload = JSON.parse(textFromB64url(p));
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== 'RS256') throw new Error('bad alg');
  if (payload.aud !== projectId || payload.iss !== `https://securetoken.google.com/${projectId}`) throw new Error('bad audience');
  if (!(payload.exp > now) || !(payload.iat <= now + 300) || typeof payload.sub !== 'string' || !UID.test(payload.sub)) {
    throw new Error('bad claims');
  }
  // v2 requires real accounts; leftover anonymous users can't trigger notifications.
  if (payload.firebase?.sign_in_provider === 'anonymous') throw new Error('anonymous');

  const jwk = (await getJwks()).find((k) => k.kid === header.kid);
  if (!jwk) throw new Error('unknown key');
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, bytesFromB64url(s), new TextEncoder().encode(`${h}.${p}`));
  if (!ok) throw new Error('bad signature');
  return payload.sub;
}

async function getJwks() {
  if (jwksCache && jwksCache.expiresAt > Date.now()) return jwksCache.keys;
  const res = await fetch(JWKS_URL);
  if (!res.ok) throw new Error(`jwks ${res.status}`);
  const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get('Cache-Control') || '')?.[1] || 3600);
  jwksCache = { keys: (await res.json()).keys, expiresAt: Date.now() + maxAge * 1000 };
  return jwksCache.keys;
}

// ---- Google OAuth access token from the service account ----

async function getAccessToken(env) {
  if (accessCache && accessCache.expiresAt > Date.now() + 60_000) return accessCache.token;
  const sa = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${b64urlFromText(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64urlFromText(JSON.stringify({
    iss: sa.client_email,
    scope: SCOPES.join(' '),
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  }))}`;
  const key = await crypto.subtle.importKey(
    'pkcs8', derFromPem(sa.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'],
  );
  const signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${unsigned}.${b64urlFromBytes(signature)}`,
    }),
  });
  if (!res.ok) throw new Error(`oauth ${res.status}`);
  const json = await res.json();
  accessCache = { token: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 };
  return json.access_token;
}

// ---- Encoding helpers ----

function bytesFromB64url(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

function textFromB64url(s) {
  return new TextDecoder().decode(bytesFromB64url(s));
}

function b64urlFromBytes(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlFromText(text) {
  return b64urlFromBytes(new TextEncoder().encode(text));
}

function derFromPem(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}
