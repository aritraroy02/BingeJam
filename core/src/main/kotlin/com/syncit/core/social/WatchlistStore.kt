package com.syncit.core.social

import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A saved title as stored in the account (watchlists/{uid}/{key}). */
data class SavedTitle(val title: TrendingTitle, val service: StreamingService, val addedAt: Long) {
    val key: String get() = keyOf(title)

    companion object {
        /** "movie_12345" / "tv_678": the same key on the phone and the PC. */
        fun keyOf(title: TrendingTitle): String = title.type.wire + "_" + title.tmdbId
    }
}

/**
 * The watchlist in the account (2026-11-12), so the phone and the PC share one list. Only the
 * owner reads or writes it.
 */
interface WatchlistStore {
    /** The account's list, newest first; null while signed out. */
    fun observe(): Flow<List<SavedTitle>?>

    suspend fun put(item: SavedTitle)

    suspend fun remove(key: String)

    object None : WatchlistStore {
        override fun observe(): Flow<List<SavedTitle>?> = flowOf(null)
        override suspend fun put(item: SavedTitle) = Unit
        override suspend fun remove(key: String) = Unit
    }
}
