// Trending titles for the app's Home (GET /trending) and the link that opens one in its app
// (GET /watch-link). Signed-in users only.
//
// Trending: TMDB's /discover (secret TMDB_READ_TOKEN, the v4 "API Read Access Token"; free for
// non-commercial use, credit TMDB in the app), per platform in India: what streams on it with a
// subscription (watch-provider data from JustWatch, credit them too), most popular first.
// Filters: ?type=movie|tv (web series), &genre=<key in GENRES>, &lang=<ISO 639-1 in LANGUAGES>.
// Without a language, Indian titles and everything are interleaved so Indian users see Indian hits.
// Cached in KV per filter (trending:v2:<type>:<genre>:<lang>); refreshed when older than
// TRENDING_MAX_AGE_MS (the default view by the cron, the rest by the next request).
//
// Watch link: Watchmode (WATCHMODE_API_KEY, as /netflix) takes TMDB's ID directly ("movie-<id>", "tv-<id>"),
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
const TRENDING_PREFIX = 'trending:v2:';
const TRENDING_MAX_AGE_MS = 12 * 3600_000;

/**
 * Filters the app may ask for (anything else -> 400, so cache keys stay bounded). Genres map to
 * TMDB genre IDs per type ("|" = any of); a genre with no TV equivalent isn't offered for series.
 */
const GENRES = {
  action: { movie: '28', tv: '10759' },
  comedy: { movie: '35', tv: '35' },
  drama: { movie: '18', tv: '18' },
  thriller: { movie: '53', tv: '9648|80' },
  romance: { movie: '10749' },
  horror: { movie: '27' },
  crime: { movie: '80', tv: '80' },
  scifi: { movie: '878', tv: '10765' },
  family: { movie: '10751', tv: '10751' },
  animation: { movie: '16', tv: '16' },
  documentary: { movie: '99', tv: '99' },
};
const LANGUAGES = new Set(['hi', 'en', 'ta', 'te', 'ml', 'kn', 'bn', 'mr', 'pa', 'gu', 'ko', 'ja', 'es']);
/** "Web series" leaves out TV that isn't a series to watch together: news, reality, soaps, talk shows. */
const NOT_SERIES = '10763,10764,10766,10767';
const SERIES_YEARS = 9;

const WATCHMODE = 'https://api.watchmode.com/v1';
const LINK_FOUND_TTL_S = 25 * 24 * 3600; // < Watchmode's 30-day refresh rule
const LINK_MISSING_TTL_S = 3 * 24 * 3600;
const NETFLIX_TITLE_URL = /^https:\/\/www\.netflix\.com\/title\/(\d{1,12})(?:[/?#]|$)/;
const HOTSTAR_URL = /^https:\/\/(?:www\.)?(?:hotstar|jiohotstar)\.com\/.*?\/(\d{6,12})(?:\/watch)?\/?(?:[?#]|$)/;
// Watchmode gives app.primevideo.com/detail?gti=amzn1.dv.gti.<uuid> (opens the title page in the app,
// checked on the OPPO with Vadhandhi, 2026-11-02); /detail/<id> pages are accepted too.
const PRIME_URL = /^https:\/\/(?:www\.|app\.)?primevideo\.com\/(?:(?:[a-z-]+\/)?(?:detail|dp)\/[A-Za-z0-9._-]{1,80}\/?|detail\?gti=amzn1\.dv\.gti\.[0-9a-f-]{36})$/;

export async function trending(request, env, { verifyIdToken, bearer, json }) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  const filter = parseFilter(new URL(request.url).searchParams);
  if (!filter) return json({ error: 'bad_filter' }, 400);
  const key = TRENDING_PREFIX + filterKey(filter);
  let body = await env.AVATARS.get(key, { type: 'json' });
  if (!body || Date.now() - (body.updatedAt || 0) > TRENDING_MAX_AGE_MS) {
    try {
      body = await refreshTrending(env, filter, key);
    } catch (e) {
      console.log('trending refresh failed', e.message);
      if (!body) return json({ updatedAt: 0, rows: [] });
    }
  }
  return json(body);
}

/** Cron: keep the default view (movies, any genre, any language) warm. */
export async function refreshTrendingIfStale(env) {
  if (!env.TMDB_READ_TOKEN) return;
  const filter = { type: 'movie', genre: '', lang: '' };
  const key = TRENDING_PREFIX + filterKey(filter);
  const body = await env.AVATARS.get(key, { type: 'json' });
  if (body && Date.now() - (body.updatedAt || 0) <= TRENDING_MAX_AGE_MS) return;
  await refreshTrending(env, filter, key);
}

/** {type, genre, lang} from the query, or null if anything is unknown. */
export function parseFilter(params) {
  const type = params.get('type') || 'movie';
  const genre = params.get('genre') || '';
  const lang = params.get('lang') || '';
  if (type !== 'movie' && type !== 'tv') return null;
  if (genre && !(GENRES[genre] && GENRES[genre][type])) return null;
  if (lang && !LANGUAGES.has(lang)) return null;
  return { type, genre, lang };
}

/** Series that started in the last SERIES_YEARS years: leaves out decades-old TV serials and reruns. */
function seriesSince() {
  return `${new Date().getUTCFullYear() - SERIES_YEARS}-01-01`;
}

function filterKey(f) {
  return `${f.type}:${f.genre || 'all'}:${f.lang || 'all'}`;
}

async function refreshTrending(env, filter, key) {
  if (!env.TMDB_READ_TOKEN) throw new Error('no TMDB token');
  const { type, genre, lang } = filter;
  const rows = await Promise.all(
    PROVIDERS.map(async ({ service, id }) => {
      let base = `/discover/${type}?watch_region=${REGION}&with_watch_providers=${id}` +
        '&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&page=1';
      if (genre) base += `&with_genres=${encodeURIComponent(GENRES[genre][type])}`;
      if (type === 'tv') base += `&without_genres=${NOT_SERIES}&first_air_date.gte=${seriesSince()}`;
      if (lang) {
        const only = await tmdb(`${base}&with_original_language=${lang}`, env);
        return { service, items: interleave(only.results || [], []).slice(0, PER_ROW).map((t) => item(t, type)) };
      }
      // No language picked: Indian titles and everything, interleaved, so Indian hits show up.
      const [indian, all] = await Promise.all([
        tmdb(`${base}&with_origin_country=${REGION}`, env),
        tmdb(base, env),
      ]);
      return { service, items: interleave(indian.results || [], all.results || []).slice(0, PER_ROW).map((t) => item(t, type)) };
    }),
  );
  const body = { updatedAt: Date.now(), type, rows };
  await env.AVATARS.put(key, JSON.stringify(body), { expirationTtl: 3 * 24 * 3600 });
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

function item(t, type) {
  const date = type === 'tv' ? t.first_air_date : t.release_date;
  const year = Number((date || '').slice(0, 4)) || null;
  return {
    id: t.id,
    type,
    title: String((type === 'tv' ? t.name || t.original_name : t.title || t.original_title) || '').slice(0, 200),
    year,
    poster: /^\/[A-Za-z0-9._-]{1,80}$/.test(t.poster_path) ? TMDB_IMAGES + t.poster_path : null,
    rating: typeof t.vote_average === 'number' ? Math.round(t.vote_average * 10) / 10 : null,
  };
}

async function tmdb(path, env) {
  // Secrets piped in from a Windows shell can carry a byte-order mark; TMDB then answers 401.
  const token = String(env.TMDB_READ_TOKEN).replace(/^\uFEFF/, '').trim();
  const res = await fetch(TMDB + path, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } });
  if (!res.ok) throw new Error(`tmdb ${res.status}`);
  return res.json();
}

// ---- GET /search?q=<title> (Home's title search, 2026-11-25) ----
// TMDB's multi search, keeping only movies and series that stream in India with a subscription on
// Netflix, JioHotstar or Prime Video, grouped like /trending ({rows: [{service, items}]}) so the app
// reuses its parser. Cached in KV per query for a day.

const SEARCH_PREFIX = 'search:v1:';
const SEARCH_TTL_S = 24 * 3600;
const SEARCH_LOOKUPS = 10;

export function normalizeQuery(q) {
  const t = String(q || '').replace(/\s+/g, ' ').trim().toLowerCase();
  return t.length >= 2 && t.length <= 60 ? t : null;
}

export async function search(request, env, { verifyIdToken, bearer, json }) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  const q = normalizeQuery(new URL(request.url).searchParams.get('q'));
  if (!q) return json({ error: 'bad_query' }, 400);
  if (!env.TMDB_READ_TOKEN) return json({ rows: [] });
  const key = SEARCH_PREFIX + q;
  const cached = await env.AVATARS.get(key, { type: 'json' });
  if (cached) return json(cached);
  try {
    const found = await tmdb(`/search/multi?query=${encodeURIComponent(q)}&include_adult=false&region=${REGION}&page=1`, env);
    const titles = (found.results || [])
      .filter((t) => (t.media_type === 'movie' || t.media_type === 'tv') && t.id && t.poster_path)
      .slice(0, SEARCH_LOOKUPS);
    const withProviders = await Promise.all(
      titles.map(async (t) => {
        const providers = await tmdb(`/${t.media_type}/${t.id}/watch/providers`, env).catch(() => null);
        const flat = (providers && providers.results && providers.results[REGION] && providers.results[REGION].flatrate) || [];
        return { t, ids: new Set(flat.map((p) => p.provider_id)) };
      }),
    );
    const body = { updatedAt: Date.now(), rows: groupByService(withProviders) };
    await env.AVATARS.put(key, JSON.stringify(body), { expirationTtl: SEARCH_TTL_S });
    return json(body);
  } catch (e) {
    console.log('search failed', e.message);
    return json({ rows: [] });
  }
}

/** [{t, ids}] → rows per service in PROVIDERS order, each title where it streams. */
export function groupByService(list) {
  return PROVIDERS.map(({ service, id }) => ({
    service,
    items: list.filter((x) => x.ids.has(id)).map((x) => item(x.t, x.t.media_type)),
  })).filter((row) => row.items.length > 0);
}

// ---- GET /watch-link?tmdb=<id>&type=movie|tv&service=NETFLIX|JIOHOTSTAR|PRIME_VIDEO ----

export async function watchLink(request, env, { verifyIdToken, bearer, json }) {
  try {
    await verifyIdToken(bearer(request), env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return new Response('Unauthorized', { status: 401 });
  }
  const params = new URL(request.url).searchParams;
  const tmdbId = params.get('tmdb') || '';
  const service = params.get('service') || '';
  const type = params.get('type') || 'movie';
  if (!/^\d{1,9}$/.test(tmdbId) || !PROVIDERS.some((p) => p.service === service) || (type !== 'movie' && type !== 'tv')) {
    return json({ error: 'bad_request' }, 400);
  }
  if (!env.WATCHMODE_API_KEY) return json({ url: null });

  const key = `wlink:${service}:${type}-${tmdbId}`;
  const cached = await env.AVATARS.get(key, { type: 'json' });
  if (cached) return json(cached);

  let url = null;
  try {
    const details = await watchmodeGet(`/title/${type}-${tmdbId}/details/?append_to_response=sources&regions=${REGION}`, env.WATCHMODE_API_KEY);
    url = pickLink(details.sources || [], service);
    if (!url) {
      // What the service's entries looked like, to extend the formats we accept (no secrets in these).
      const seen = (details.sources || []).filter((s) => s.region === REGION).map((s) => `${s.source_id}|${s.name}|${s.type}|${s.web_url}|${s.android_url || ''}`);
      console.log('watch-link: no link', service, `${type}-${tmdbId}`, JSON.stringify(seen).slice(0, 1500));
    }
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
