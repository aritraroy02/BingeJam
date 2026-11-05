// Push relay + profile-picture store for Vyro (Cloudflare Worker, free tier).
// Routes: POST / (notify, below), PUT|DELETE /avatar and GET /avatar/<uid>, GET /netflix (title lookup),
// GET /voice/<code> (room voice chat WebSocket, src/voice.js), GET /trending and GET /watch-link
// (Home's trending titles and the link that opens one, src/trending.js).
//
// Firebase Cloud Messaging is free, but sending needs a trusted server, and Cloud Functions need
// the paid Blaze plan. This Worker is that server:
//   1. The app sends POST {type, to, messageId?} with "Authorization: Bearer <Firebase ID token>"
//      right after writing a direct message or a friend request (or deleting a message for
//      everyone: type dm_deleted, a data-only message that clears the text from the notification).
//   2. We verify the ID token (signed in, not anonymous), then read the message/request from the
//      Realtime Database ourselves. The notification text never comes from the request, so a
//      client can't make us send arbitrary text, and only to someone it actually wrote to.
//   3. We send it to every token in pushTokens/{to} through FCM HTTP v1, and drop dead tokens.
//
// Secret FIREBASE_SERVICE_ACCOUNT: the Firebase service account JSON (Admin SDK key). It gives
// admin access to the database and FCM, so it lives only in Cloudflare's secret store.

import { connectVoice, VoiceRoom } from './voice.js';
import { refreshTrendingIfStale, trending, watchLink } from './trending.js';

export { VoiceRoom };

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
/** A deleted message's notification is only retracted if the message is younger than this. */
const RETRACT_MAX_AGE_MS = 24 * 60 * 60_000;

let jwksCache = null; // { keys, expiresAt }
let accessCache = null; // { token, expiresAt }

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path.startsWith('/avatar/') && request.method === 'GET') return serveAvatar(path, env);
    if (path === '/avatar' && (request.method === 'PUT' || request.method === 'DELETE')) return changeAvatar(request, env);
    if (path === '/netflix' && request.method === 'GET') return netflixLookup(request, env);
    if (path === '/trending' && request.method === 'GET') return trending(request, env, { verifyIdToken, bearer, json });
    if (path === '/watch-link' && request.method === 'GET') return watchLink(request, env, { verifyIdToken, bearer, json });
    if (path.startsWith('/voice/') && request.method === 'GET') {
      return connectVoice(request, env, path.slice('/voice/'.length), { verifyIdToken, bearer, Database, clip });
    }
    if (path !== '/' || request.method !== 'POST') return new Response('Not found', { status: 404 });
    return notify(request, env);
  },

  /** Cron trigger (wrangler.toml): tell every phone about a new release, once per version; keep trending fresh. */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(announceRelease(env).catch((e) => console.error('release check failed', e && e.message)));
    ctx.waitUntil(refreshTrendingIfStale(env).catch((e) => console.error('trending refresh failed', e && e.message)));
  },
};

// ---- New version announcements (cron, every 30 min) ----
//
// Phones subscribe to the FCM topic "updates". When the latest GitHub release of the public
// releases repo changes, we send one data-only message with its version; the app shows a
// notification only if that version is newer than the one installed. KV remembers the last
// announced version. The very first run only records the current release (no announcement).

const RELEASES_API = 'https://api.github.com/repos/aritraroy02/sync-it-releases/releases/latest';
const ANNOUNCED_KEY = 'release:announced';
const UPDATES_TOPIC = 'updates';

async function announceRelease(env) {
  const res = await fetch(RELEASES_API, {
    headers: { 'User-Agent': 'bingejam-push-relay', Accept: 'application/vnd.github+json' },
  });
  if (!res.ok) throw new Error(`github ${res.status}`);
  const release = await res.json();
  const version = String(release.tag_name || '').replace(/^v/, '');
  if (!/^\d+\.\d+\.\d+$/.test(version) || release.draft || release.prerelease) return;

  const announced = await env.AVATARS.get(ANNOUNCED_KEY);
  if (announced === version) return;
  if (announced !== null) {
    // First non-empty line of the release notes, without its "- " bullet.
    const notes = String(release.body || '').split('\n').map((l) => l.replace(/^[-*\s]+/, '').trim()).find((l) => l) || '';
    const accessToken = await getAccessToken(env);
    const send = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          topic: UPDATES_TOPIC,
          data: { type: 'update', version, notes: clip(notes, 200) },
          android: { priority: 'HIGH' },
        },
      }),
    });
    if (!send.ok) throw new Error(`FCM topic send ${send.status}: ${(await send.text()).slice(0, 200)}`);
    console.log('announced', version);
  }
  await env.AVATARS.put(ANNOUNCED_KEY, version);
}

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
    if (body.type === 'group_message' || body.type === 'group_invite' || body.type === 'room_ping') {
      try {
        return await groupNotify(new Database(env), env, sender, body);
      } catch (e) {
        console.error('relay failed', e && e.message);
        return new Response('Relay error', { status: 502 });
      }
    }
    const to = String(body.to ?? '');
    if (!UID.test(to) || to === sender) return new Response('Bad request', { status: 400 });

    try {
      const db = new Database(env);
      let note = null;
      if (body.type === 'dm') note = await directMessage(db, sender, to, String(body.messageId ?? ''));
      else if (body.type === 'dm_deleted') note = await deletedMessage(db, sender, to, String(body.messageId ?? ''));
      else if (body.type === 'friend_request') note = await friendRequest(db, sender, to);
      else if (body.type === 'join_request') note = await joinRequest(db, sender, to);
      else if (body.type === 'invite') note = await roomInvite(db, sender, to);
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

// ---- Netflix title lookup (GET /netflix?title=…&durationMs=…) ----
//
// Netflix's MediaSession publishes only the title, length and poster: no ID. To let a friend's
// "Play in Netflix" open the exact movie (netflix.com/watch/<id>), we look the title up in
// Watchmode's catalogue (secret WATCHMODE_API_KEY; free Developer plan = non-commercial, credit
// Watchmode in the app, refresh cached data within 30 days). Signed-in users only. Answers are
// cached in KV per title + length, so each movie costs Watchmode credits once a month at most.

const WATCHMODE = 'https://api.watchmode.com/v1';
const NETFLIX_REGION = 'IN';
const NETFLIX_SOURCE_ID = 203;
const NETFLIX_TITLE_URL = /^https:\/\/www\.netflix\.com\/title\/(\d{1,12})(?:[/?#]|$)/;
/** Netflix's length includes credits; catalogue runtimes are rounded. */
const RUNTIME_TOLERANCE_MS = 15 * 60_000;
const FOUND_TTL_S = 25 * 24 * 3600; // < Watchmode's 30-day refresh rule
const MISSING_TTL_S = 3 * 24 * 3600;
const MAX_CANDIDATES = 3;

async function netflixLookup(request, env) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  if (!env.WATCHMODE_API_KEY) return json({ netflixId: null });
  const params = new URL(request.url).searchParams;
  const title = (params.get('title') || '').trim();
  const durationMs = Number(params.get('durationMs')) || 0;
  if (!title || title.length > 200) return json({ error: 'bad_title' }, 400);

  const minutes = durationMs > 0 ? Math.round(durationMs / 60_000) : 0;
  const key = `nflx:${normalizeTitle(title)}|${minutes}`;
  const cached = await env.AVATARS.get(key, { type: 'json' });
  if (cached) return json(cached);

  let netflixId = null;
  try {
    netflixId = await findNetflixId(title, durationMs, env.WATCHMODE_API_KEY);
  } catch (e) {
    console.log('watchmode lookup failed', e.message);
    return json({ netflixId: null }); // not cached: try again next time
  }
  const body = { netflixId, source: netflixId ? 'watchmode' : null };
  await env.AVATARS.put(key, JSON.stringify(body), { expirationTtl: netflixId ? FOUND_TTL_S : MISSING_TTL_S });
  return json(body);
}

/** Exact-name movie matches, checked in order: runtime close to Netflix's length, on Netflix in India. */
async function findNetflixId(title, durationMs, apiKey) {
  const wanted = normalizeTitle(title);
  const search = await watchmode(`/search/?search_field=name&search_value=${encodeURIComponent(title)}&types=movie,tv_movie,tv_special`, apiKey);
  const candidates = (search.title_results || [])
    .filter((t) => t.resultType === 'title' && normalizeTitle(t.name || '') === wanted)
    .slice(0, MAX_CANDIDATES);
  for (const c of candidates) {
    const details = await watchmode(`/title/${encodeURIComponent(c.id)}/details/?append_to_response=sources&regions=${NETFLIX_REGION}`, apiKey);
    const runtimeMs = (details.runtime_minutes || 0) * 60_000;
    if (durationMs > 0 && runtimeMs > 0 && Math.abs(runtimeMs - durationMs) > RUNTIME_TOLERANCE_MS) continue;
    for (const s of details.sources || []) {
      if (s.region !== NETFLIX_REGION || (s.source_id !== NETFLIX_SOURCE_ID && s.name !== 'Netflix')) continue;
      const m = NETFLIX_TITLE_URL.exec(s.web_url || '');
      if (m) return m[1];
    }
  }
  return null;
}

async function watchmode(path, apiKey) {
  const res = await fetch(WATCHMODE + path, { headers: { 'X-API-Key': apiKey } });
  if (!res.ok) throw new Error(`watchmode ${res.status}`);
  return res.json();
}

/** Lower case, punctuation and spacing runs folded, so "Spider-Man: Homecoming" matches "spider man homecoming". */
function normalizeTitle(s) {
  return s.toLowerCase().normalize('NFKC').replace(/[\s\p{P}]+/gu, ' ').trim();
}

// ---- What to send ----

async function directMessage(db, from, to, messageId) {
  if (!PUSH_ID.test(messageId)) return null;
  // Same as DmIds.dmId in the app: the two uids sorted, joined with "_".
  const dmId = from < to ? `${from}_${to}` : `${to}_${from}`;
  const msg = await db.get(`dms/${dmId}/${messageId}`);
  // Deleted for everyone (no text) or a "watched together" card (kind, no from): nothing to send.
  if (!msg || msg.from !== from || msg.deleted || msg.kind || typeof msg.text !== 'string' || !isRecent(msg.sentAt)) return null;
  const profile = (await db.get(`users/${from}`)) || {};
  return {
    title: profile.displayName || 'New message',
    body: clip(msg.text, 240),
    channel: 'messages',
    data: { type: 'dm', uid: from },
  };
}

/**
 * `from` deleted one of their messages for everyone. If it's still their latest message to `to`,
 * the notification on `to`'s phones may be showing its text: send a data-only message so the app
 * replaces that notification (same tag "dm-<from>") with "This message was deleted", silently and
 * only if it's still there. A newer message from `from` already replaced it, so then nothing is sent.
 */
async function deletedMessage(db, from, to, messageId) {
  if (!PUSH_ID.test(messageId)) return null;
  const dmId = from < to ? `${from}_${to}` : `${to}_${from}`;
  const msg = await db.get(`dms/${dmId}/${messageId}`);
  if (!msg || msg.from !== from || msg.deleted !== true || !isRecent(msg.sentAt, RETRACT_MAX_AGE_MS)) return null;
  const later = (await db.get(`dms/${dmId}`, `?orderBy=${encodeURIComponent('"sentAt"')}&startAt=${msg.sentAt + 1}`)) || {};
  if (Object.values(later).some((m) => m && m.from === from && typeof m.text === 'string')) return null;
  return { dataOnly: true, data: { type: 'dm_deleted', uid: from } };
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

/** Music and YouTube rooms are jams; the rest are watch parties (same as ActivityApp in the app). */
const JAM_APPS = new Set(['spotify', 'ytmusic', 'amazonmusic', 'youtube']);
const APP_NAMES = {
  netflix: 'Netflix', jiohotstar: 'JioHotstar', primevideo: 'Prime Video', spotify: 'Spotify',
  ytmusic: 'YouTube Music', amazonmusic: 'Amazon Music', youtube: 'YouTube',
};

function whoFrom(item) {
  return item.fromDisplayName ? `${item.fromDisplayName} (@${item.fromUsername})` : `@${item.fromUsername}`;
}

/** A friend asked to join what `to` is watching: pop up so the host can let them in. */
async function joinRequest(db, from, to) {
  const req = await db.get(`joinRequests/${to}/${from}`);
  if (!req || req.status !== 'pending' || !isRecent(req.sentAt)) return null;
  const jam = JAM_APPS.has(req.app);
  const what = typeof req.title === 'string' && req.title ? req.title : APP_NAMES[req.app] || 'your room';
  return {
    title: jam ? 'Wants to jam with you' : 'Wants to join you',
    body: clip(`${whoFrom(req)} wants to ${jam ? 'jam to' : 'watch'} ${what} with you. Tap to let them in.`, 240),
    channel: 'requests',
    data: { type: 'join_request', uid: from },
  };
}

/** `from` invited `to` into their room. */
async function roomInvite(db, from, to) {
  const inv = await db.get(`invites/${to}/${from}`);
  if (!inv || !isRecent(inv.sentAt)) return null;
  const jam = JAM_APPS.has(inv.app);
  const what = typeof inv.title === 'string' && inv.title ? ` ${jam ? 'to ' : ''}${inv.title}` : '';
  return {
    title: jam ? 'Jam invite' : 'Watch party invite',
    body: clip(`${whoFrom(inv)} invited you to ${jam ? 'jam' : 'watch'}${what}. Tap to join.`, 240),
    channel: 'requests',
    data: { type: 'invite', uid: from },
  };
}

// ---- Groups (2026-11-05) ----
//
// group_message {group, messageId}: notify every other member (one notification per group, tag group-<id>).
// group_invite  {group, to}: someone who chose "Ask me first" was invited (groupInvites/{to}/{group}).
// room_ping     {group, to}: "join the room"; both must be members and the room live; once per 2 min per pair.

/** Seconds a ping from one member to another blocks the next one (KV minimum TTL is 60). */
const PING_COOLDOWN_S = 120;

async function groupNotify(db, env, sender, body) {
  const gid = String(body.group ?? '');
  if (!PUSH_ID.test(gid)) return new Response('Bad request', { status: 400 });
  const group = await db.get(`groups/${gid}`);
  const members = (group && group.members) || {};

  if (body.type === 'group_invite') {
    const to = String(body.to ?? '');
    if (!UID.test(to) || to === sender) return new Response('Bad request', { status: 400 });
    const inv = await db.get(`groupInvites/${to}/${gid}`);
    if (!inv || inv.from !== sender || !isRecent(inv.sentAt)) return new Response(null, { status: 204 });
    if (await db.get(`blocked/${to}/${sender}`)) return new Response(null, { status: 204 });
    await sendToAll(db, env, to, {
      title: 'Group invite',
      body: clip(`${inv.fromName || 'A friend'} invited you to ${inv.groupName || 'a group'}. Tap to answer.`, 240),
      channel: 'requests',
      data: { type: 'group_invite', uid: gid },
    });
    return new Response(null, { status: 204 });
  }

  if (!members[sender]) return new Response(null, { status: 204 });
  const profile = (await db.get(`users/${sender}`)) || {};
  const name = profile.displayName || 'Someone';

  if (body.type === 'group_message') {
    const messageId = String(body.messageId ?? '');
    if (!PUSH_ID.test(messageId)) return new Response('Bad request', { status: 400 });
    const msg = await db.get(`groupMessages/${gid}/${messageId}`);
    if (!msg || msg.from !== sender || typeof msg.text !== 'string' || !isRecent(msg.sentAt)) return new Response(null, { status: 204 });
    const note = {
      title: clip(group.name || 'Group', 60),
      body: clip(`${name}: ${msg.text}`, 240),
      channel: 'messages',
      data: { type: 'group', uid: gid },
    };
    await Promise.all(Object.keys(members).filter((uid) => uid !== sender && UID.test(uid)).map(async (uid) => {
      if (!(await db.get(`blocked/${uid}/${sender}`))) await sendToAll(db, env, uid, note);
    }));
    return new Response(null, { status: 204 });
  }

  // room_ping
  const to = String(body.to ?? '');
  if (!UID.test(to) || to === sender || !members[to]) return new Response(null, { status: 204 });
  const room = await db.get(`groupRooms/${gid}`);
  if (!room || !room.in || !Object.keys(room.in).length) return new Response(null, { status: 204 });
  const key = `ping:${gid}:${sender}:${to}`;
  if (await env.AVATARS.get(key)) return new Response(null, { status: 204 });
  await env.AVATARS.put(key, '1', { expirationTtl: PING_COOLDOWN_S });
  if (await db.get(`blocked/${to}/${sender}`)) return new Response(null, { status: 204 });
  await sendToAll(db, env, to, {
    title: clip(group.name || 'Group', 60),
    body: clip(`${name} wants you in the room. Tap to join.`, 240),
    channel: 'requests',
    data: { type: 'room_ping', uid: gid },
  });
  return new Response(null, { status: 204 });
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
        // dataOnly: handled by the app itself (VyroMessagingService), even in the background.
        message: note.dataOnly ? { token, data: note.data, android: { priority: 'HIGH' } } : {
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

function isRecent(sentAt, maxAgeMs = MAX_AGE_MS) {
  return typeof sentAt === 'number' && Math.abs(Date.now() - sentAt) <= maxAgeMs;
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
