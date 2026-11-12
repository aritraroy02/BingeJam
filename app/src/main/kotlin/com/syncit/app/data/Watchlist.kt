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
 * friends join). Newest first, at most [MAX] titles. Since 2026-11-12 the list lives in the
 * account (shared with the PC app, see AppContainer); this keeps a copy for offline use.
 */
class Watchlist(context: Context) {
    private val prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<WatchlistItem>> = _items.asStateFlow()

    /** Set while signed in: each change goes to the account too (a title added, or a key removed). */
    @Volatile
    var onChange: ((added: WatchlistItem?, removedKey: String?) -> Unit)? = null

    fun contains(title: TrendingTitle): Boolean = _items.value.any { it.key == WatchlistItem.keyOf(title) }

    /** Adds [title] (on [service]) or, if it's already saved, removes it. True if it's now saved. */
    @Synchronized
    fun toggle(title: TrendingTitle, service: StreamingService): Boolean {
        val key = WatchlistItem.keyOf(title)
        val current = _items.value
        val removing = current.any { it.key == key }
        val added = if (removing) null else WatchlistItem(title, service, System.currentTimeMillis())
        val next = if (removing) current.filterNot { it.key == key } else (listOf(added!!) + current).take(MAX)
        save(next)
        onChange?.invoke(added, if (removing) key else null)
        return !removing
    }

    /** The account's list arrived (or changed on the PC). */
    @Synchronized
    fun replace(items: List<WatchlistItem>) = save(items.distinctBy { it.key }.sortedByDescending { it.addedAt }.take(MAX))

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
