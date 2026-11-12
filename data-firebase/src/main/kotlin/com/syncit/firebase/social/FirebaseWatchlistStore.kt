package com.syncit.firebase.social

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.syncit.core.lookup.TitleType
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService
import com.syncit.core.social.SavedTitle
import com.syncit.core.social.WatchlistStore
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.long
import com.syncit.firebase.snapshots
import com.syncit.firebase.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * watchlists/{uid}/{type_tmdbId} = {id, t, y?, p?, r?, k, s, a}: the same short fields the phone
 * used locally, and the desktop app reads them too (desktop/src/renderer/src/data/watchlist.ts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FirebaseWatchlistStore(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : WatchlistStore {

    override fun observe(): Flow<List<SavedTitle>?> = session.uid.flatMapLatest { uid ->
        if (uid == null) {
            flowOf(null)
        } else {
            db.getReference("watchlists").child(uid).snapshots().map<DataSnapshot?, List<SavedTitle>?> { s ->
                s?.children?.mapNotNull(::parse).orEmpty().sortedByDescending { it.addedAt }
            }.catch { emit(null) }
        }
    }

    override suspend fun put(item: SavedTitle) {
        val uid = session.requireUid()
        val t = item.title
        val value = buildMap<String, Any> {
            put("id", t.tmdbId)
            put("t", t.title.take(200))
            t.year?.let { put("y", it) }
            t.posterUrl?.takeIf { it.startsWith("https://image.tmdb.org/") && it.length <= 500 }?.let { put("p", it) }
            t.rating?.takeIf { it in 0.0..10.0 }?.let { put("r", it) }
            put("k", t.type.wire)
            put("s", item.service.name)
            put("a", item.addedAt)
        }
        db.getReference("watchlists").child(uid).child(item.key).setValue(value).await()
    }

    override suspend fun remove(key: String) {
        val uid = session.requireUid()
        db.getReference("watchlists").child(uid).child(key).removeValue().await()
    }

    private fun parse(s: DataSnapshot): SavedTitle? {
        val id = s.child("id").long() ?: return null
        val title = s.child("t").string() ?: return null
        val service = StreamingService.entries.firstOrNull { it.name == s.child("s").string() } ?: return null
        return SavedTitle(
            TrendingTitle(
                tmdbId = id,
                title = title,
                year = s.child("y").long()?.toInt(),
                posterUrl = s.child("p").string(),
                rating = (s.child("r").value as? Number)?.toDouble(),
                type = TitleType.fromWire(s.child("k").string()),
            ),
            service,
            s.child("a").long() ?: 0L,
        )
    }
}
