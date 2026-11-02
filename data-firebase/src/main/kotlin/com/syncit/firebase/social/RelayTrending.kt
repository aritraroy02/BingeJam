package com.syncit.firebase.social

import android.net.Uri
import android.util.Log
import com.syncit.core.lookup.TrendingCatalog
import com.syncit.core.lookup.TrendingRow
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService
import com.syncit.firebase.FirebaseConfig
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Home's trending rows (relay GET /trending, TMDB) and watch links (GET /watch-link, Watchmode).
 * The relay caches both; we keep trending for [TRENDING_TTL_MS] and links for the process.
 */
internal class RelayTrending(
    private val session: FirebaseSession,
    private val baseUrl: String = FirebaseConfig.PUSH_RELAY_URL,
) : TrendingCatalog {
    private val links = ConcurrentHashMap<String, String>() // "<service>:<tmdbId>" -> url, or "" for none

    @Volatile private var cached: Pair<Long, List<TrendingRow>>? = null

    override suspend fun trending(): List<TrendingRow> {
        cached?.let { (at, rows) -> if (System.currentTimeMillis() - at < TRENDING_TTL_MS && rows.isNotEmpty()) return rows }
        val body = get(Uri.parse(baseUrl).buildUpon().appendPath("trending").build().toString()) ?: return cached?.second.orEmpty()
        val rows = parseTrending(body)
        if (rows.isNotEmpty()) cached = System.currentTimeMillis() to rows
        return rows
    }

    override suspend fun watchLink(title: TrendingTitle, service: StreamingService): String? {
        val key = "${service.name}:${title.tmdbId}"
        links[key]?.let { return it.ifEmpty { null } }
        val url = Uri.parse(baseUrl).buildUpon()
            .appendPath("watch-link")
            .appendQueryParameter("tmdb", title.tmdbId.toString())
            .appendQueryParameter("service", service.name)
            .build().toString()
        val body = get(url) ?: return null // failed: not remembered, retried next time
        val link = body.optString("url").takeIf { TrendingCatalog.isSafeLink(service, it) }
        links[key] = link.orEmpty()
        return link
    }

    private suspend fun get(url: String): JSONObject? {
        if (baseUrl.isBlank()) return null
        val user = session.auth.currentUser?.takeUnless { it.isAnonymous } ?: return null
        return try {
            val idToken = user.getIdToken(false).await().token ?: return null
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Authorization", "Bearer $idToken")
                    if (connection.responseCode !in 200..299) error("relay answered ${connection.responseCode}")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Trending request failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "SyncItTrending"
        private const val TRENDING_TTL_MS = 60 * 60_000L
        private val POSTER = Regex("""https://image\.tmdb\.org/t/p/w\d{2,4}/[A-Za-z0-9._-]{1,80}""")

        /** The relay's JSON; unknown services, titles without a name and odd poster URLs are dropped. */
        fun parseTrending(body: JSONObject): List<TrendingRow> {
            val rows = body.optJSONArray("rows") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val service = StreamingService.fromId(row.optString("service")) ?: return@mapNotNull null
                val items = row.optJSONArray("items") ?: return@mapNotNull null
                val titles = (0 until items.length()).mapNotNull { j ->
                    val t = items.optJSONObject(j) ?: return@mapNotNull null
                    val id = t.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                    val name = t.optString("title").trim().take(200).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    TrendingTitle(
                        tmdbId = id,
                        title = name,
                        year = t.optInt("year", 0).takeIf { it in 1880..2200 },
                        posterUrl = t.optString("poster").takeIf { POSTER.matches(it) },
                        rating = t.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it in 0.0..10.0 },
                    )
                }
                if (titles.isEmpty()) null else TrendingRow(service, titles)
            }
        }
    }
}
