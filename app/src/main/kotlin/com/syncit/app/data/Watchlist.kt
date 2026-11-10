package com.syncit.app.data

import android.content.Context
import androidx.core.content.edit
import com.syncit.core.lookup.TitleType
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A title saved for later, with the app it was trending on (where "Watch" opens it). */
data class WatchlistItem(val title: TrendingTitle, val service: StreamingService, val addedAt: Long) {
    val key: String get() = keyOf(title)

    companion object {
        fun keyOf(title: TrendingTitle): String = title.type.wire + ":" + title.tmdbId
    }
}

/**
 * "Save for later" from the Watch tab's trending posters (2026-11-10: something to do before any
 * friends join). Kept on this phone only, newest first, at most [MAX] titles.
 */
class Watchlist(context: Context) {
    private val prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<WatchlistItem>> = _items.asStateFlow()

    fun contains(title: TrendingTitle): Boolean = _items.value.any { it.key == WatchlistItem.keyOf(title) }

    /** Adds [title] (on [service]) or, if it's already saved, removes it. True if it's now saved. */
    @Synchronized
    fun toggle(title: TrendingTitle, service: StreamingService): Boolean {
        val key = WatchlistItem.keyOf(title)
        val current = _items.value
        val next = if (current.any { it.key == key }) {
            current.filterNot { it.key == key }
        } else {
            (listOf(WatchlistItem(title, service, System.currentTimeMillis())) + current).take(MAX)
        }
        save(next)
        return next.size > current.size
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<WatchlistItem>) {
        _items.value = list
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject()
                    .put("id", item.title.tmdbId)
                    .put("t", item.title.title)
                    .put("y", item.title.year ?: JSONObject.NULL)
                    .put("p", item.title.posterUrl ?: JSONObject.NULL)
                    .put("r", item.title.rating ?: JSONObject.NULL)
                    .put("k", item.title.type.wire)
                    .put("s", item.service.name)
                    .put("a", item.addedAt),
            )
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private fun load(): List<WatchlistItem> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val service = StreamingService.entries.firstOrNull { it.name == o.optString("s") } ?: return@mapNotNull null
            WatchlistItem(
                TrendingTitle(
                    tmdbId = o.getLong("id"),
                    title = o.getString("t"),
                    year = if (o.isNull("y")) null else o.getInt("y"),
                    posterUrl = if (o.isNull("p")) null else o.getString("p"),
                    rating = if (o.isNull("r")) null else o.getDouble("r"),
                    type = TitleType.fromWire(o.optString("k")),
                ),
                service,
                o.optLong("a"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "items"
        const val MAX = 100
    }
}
