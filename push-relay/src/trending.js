// Trending titles for the app's Home (GET /trending) and the link that opens one in its app
// (GET /watch-link). Signed-in users only.
//
// Trending: TMDB's /discover (secret TMDB_READ_TOKEN, the v4 "API Read Access Token"; free for
// non-commercial use, credit TMDB in the app), per platform in India: what streams on it with a
// subscription (watch-provider data from JustWatch, credit them too), most popular first. Two
// lists per platform, Indian films and everything, interleaved so Indian users see Indian hits.
// Cached in KV; refreshed when older than TRENDING_MAX_AGE_MS (by the cron or the next request).
//
// Watch link: Watchmode (WATCHMODE_API_KEY, as /netflix) takes TMDB's ID directly ("movie-<id>"),
// so the match is exact, and lists the title's page on each service in India. We pass on only
// links in known formats: Netflix -> netflix.com/watch/<id> (starts it), JioHotstar ->
// hotstar.com/in/movies/x/<id>/watch (starts it), Prime Video -> its primevideo.com detail page.
// Cached in KV per title + service, so each costs Watchmode credits once a month at most.

const TMDB = 'https://api.themoviedb.org/3';
const TMDB_IMAGES = 'https://image.tmdb.org/t/p/w342';
const REGION = 'IN';
/** TMDB watch-provider IDs in India (GET /watch/providers/movie?watch_region=IN, 2026-11-02). */
const PROVIDERS = [
  { service: 'NETFLIX', id: 8 },
  { service: 'JIOHOTSTAR', id: 2336 },
  { service: 'PRIME_VIDEO', id: 119 },
];
const PER_ROW = 20;
const TRENDING_KEY = 'trending:v1';
const TRENDING_MAX_AGE_MS = 12 * 3600_000;

const WATCHMODE = 'https://api.watchmode.com/v1';
const LINK_FOUND_TTL_S = 25 * 24 * 3600; // < Watchmode's 30-day refresh rule
const LINK_MISSING_TTL_S = 3 * 24 * 3600;
const NETFLIX_TITLE_URL = /^https:\/\/www\.netflix\.com\/title\/(\d{1,12})(?:[/?#]|$)/;
const HOTSTAR_URL = /^https:\/\/(?:www\.)?(?:hotstar|jiohotstar)\.com\/.*?\/(\d{6,12})(?:\/watch)?\/?(?:[?#]|$)/;
const PRIME_URL = /^https:\/\/(?:www\.|app\.)?primevideo\.com\/(?:[a-z-]+\/)?(?:detail|dp)\/[A-Za-z0-9._-]{1,80}\/?(?:[?#].*)?$/;

export async function trending(request, env, { verifyIdToken, bearer, json }) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  let body = await env.AVATARS.get(TRENDING_KEY, { type: 'json' });
  if (!body || Date.now() - (body.updatedAt || 0) > TRENDING_MAX_AGE_MS) {
    try {
      body = await refreshTrending(env);
    } catch (e) {
      console.log('trending refresh failed', e.message);
      if (!body) return json({ updatedAt: 0, rows: [] });
    }
  }
  return json(body);
}

/** Cron: keep the cache warm so the first person each morning doesn't wait for TMDB. */
export async function refreshTrendingIfStale(env) {
  if (!env.TMDB_READ_TOKEN) return;
  const body = await env.AVATARS.get(TRENDING_KEY, { type: 'json' });
  if (body && Date.now() - (body.updatedAt || 0) <= TRENDING_MAX_AGE_MS) return;
  await refreshTrending(env);
}

async function refreshTrending(env) {
  if (!env.TMDB_READ_TOKEN) throw new Error('no TMDB token');
  const rows = await Promise.all(
    PROVIDERS.map(async ({ service, id }) => {
      const base = `/discover/movie?watch_region=${REGION}&with_watch_providers=${id}` +
        '&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&page=1';
      const [indian, all] = await Promise.all([
        tmdb(`${base}&with_origin_country=${REGION}`, env),
        tmdb(base, env),
      ]);
      return { service, items: interleave(indian.results || [], all.results || []).slice(0, PER_ROW).map(item) };
    }),
  );
  const body = { updatedAt: Date.now(), rows };
  await env.AVATARS.put(TRENDING_KEY, JSON.stringify(body));
  return body;
}

function interleave(a, b) {
  const seen = new Set();
  const out = [];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    for (const t of [a[i], b[i]]) {
      if (t && t.id && t.poster_path && !seen.has(t.id)) {
        seen.add(t.id);
        out.push(t);
      }
    }
  }
  return out;
}

function item(t) {
  const year = Number((t.release_date || '').slice(0, 4)) || null;
  return {
    id: t.id,
    type: 'movie',
    title: String(t.title || t.original_title || '').slice(0, 200),
    year,
    poster: /^\/[A-Za-z0-9._-]{1,80}$/.test(t.poster_path) ? TMDB_IMAGES + t.poster_path : null,
    rating: typeof t.vote_average === 'number' ? Math.round(t.vote_average * 10) / 10 : null,
  };
}

async function tmdb(path, env) {
  const res = await fetch(TMDB + path, { headers: { Authorization: `Bearer ${env.TMDB_READ_TOKEN}`, Accept: 'application/json' } });
  if (!res.ok) throw new Error(`tmdb ${res.status}`);
  return res.json();
}

// ---- GET /watch-link?tmdb=<id>&service=NETFLIX|JIOHOTSTAR|PRIME_VIDEO ----

export async function watchLink(request, env, { verifyIdToken, bearer, json }) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  const params = new URL(request.url).searchParams;
  const tmdbId = params.get('tmdb') || '';
  const service = params.get('service') || '';
  if (!/^\d{1,9}$/.test(tmdbId) || !PROVIDERS.some((p) => p.service === service)) return json({ error: 'bad_request' }, 400);
  if (!env.WATCHMODE_API_KEY) return json({ url: null });

  const key = `wlink:${service}:movie-${tmdbId}`;
  const cached = await env.AVATARS.get(key, { type: 'json' });
  if (cached) return json(cached);

  let url = null;
  try {
    const details = await watchmodeGet(`/title/movie-${tmdbId}/details/?append_to_response=sources&regions=${REGION}`, env.WATCHMODE_API_KEY);
    url = pickLink(details.sources || [], service);
  } catch (e) {
    console.log('watch-link lookup failed', e.message);
    return json({ url: null }); // not cached: try again next time
  }
  const body = { url };
  await env.AVATARS.put(key, JSON.stringify(body), { expirationTtl: url ? LINK_FOUND_TTL_S : LINK_MISSING_TTL_S });
  return json(body);
}

/** The service's link in India, rewritten to the form that opens (and for Netflix/JioHotstar starts) it. */
export function pickLink(sources, service) {
  for (const s of sources) {
    if (s.region !== REGION) continue;
    const name = String(s.name || '');
    const web = String(s.web_url || '');
    if (service === 'NETFLIX' && (s.source_id === 203 || name === 'Netflix')) {
      const m = NETFLIX_TITLE_URL.exec(web);
      if (m) return `https://www.netflix.com/watch/${m[1]}`;
    } else if (service === 'JIOHOTSTAR' && /hotstar/i.test(name)) {
      const m = HOTSTAR_URL.exec(web);
      if (m) return `https://www.hotstar.com/in/movies/x/${m[1]}/watch`;
    } else if (service === 'PRIME_VIDEO' && /prime video|amazon prime/i.test(name)) {
      if (PRIME_URL.test(web)) return web;
    }
  }
  return null;
}

async function watchmodeGet(path, apiKey) {
  const res = await fetch(WATCHMODE + path, { headers: { 'X-API-Key': apiKey } });
  if (!res.ok) throw new Error(`watchmode ${res.status}`);
  return res.json();
}
