package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.RoomPreview

/** Small pure helpers for jams: track identity and matching. */
internal object JamTracks {

    private val WHITESPACE = Regex("""\s+""")

    fun normalizedTitle(title: String?): String? =
        title?.trim()?.lowercase()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

    /**
     * A track's identity as a string: its public playableRef when the app publishes one, else its
     * title (normalised). Null when neither is known. For "is this a new track?" use
     * [ItemKeyTracker] / [sameItem], which don't treat a ref arriving after the title as a change.
     */
    fun key(playableRef: String?, title: String?): String? = playableRef ?: normalizedTitle(title)

    fun key(preview: RoomPreview): String? = key(preview.playableRef, preview.title)

    fun key(metadata: NowPlayingMetadata?): String? = key(metadata?.playableRef, metadata?.title)

    /**
     * Are two readings the same track? Refs decide when both have one; otherwise titles (the
     * subtitle is left out: apps often publish it a moment after the title). Null = can't tell.
     */
    fun sameItem(refA: String?, titleA: String?, refB: String?, titleB: String?): Boolean? {
        if (refA != null && refB != null) return refA == refB
        val a = normalizedTitle(titleA) ?: return null
        val b = normalizedTitle(titleB) ?: return null
        return a == b
    }

    fun sameItem(a: NowPlayingMetadata?, b: NowPlayingMetadata?): Boolean? =
        sameItem(a?.playableRef, a?.title, b?.playableRef, b?.title)

    /**
     * Is [local] the host's track? playableRef equality when both sides have one, otherwise
     * [RoomPreview.matches] (title, plus length when both know it). Null = can't tell (no title).
     */
    fun matches(preview: RoomPreview, local: NowPlayingMetadata?): Boolean? {
        val hostRef = preview.playableRef
        val localRef = local?.playableRef
        if (hostRef != null && localRef != null) return hostRef == localRef
        return preview.matches(local)
    }

    /** "<title> <subtitle>" for PLAY_FROM_SEARCH (Spotify plays the top result), or null. */
    fun searchQuery(preview: RoomPreview): String? {
        val title = preview.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val subtitle = preview.subtitle?.trim()?.takeIf { it.isNotEmpty() }
        return if (subtitle != null) "$title $subtitle" else title
    }

    /**
     * The app's live position (same formula as :sync-engine's PositionMath, which :media can't
     * depend on):
     *
     *   live = position + (nowElapsed - lastPositionUpdateTime) * speed   while PLAYING
     *   live = position                                                  otherwise
     *
     * Both times are SystemClock.elapsedRealtime(). Unknown position stays -1.
     */
    fun livePositionMs(snapshot: PlaybackSnapshot, nowElapsedMs: Long): Long {
        if (snapshot.positionMs < 0) return snapshot.positionMs
        if (snapshot.state != LocalPlaybackState.PLAYING || snapshot.lastPositionUpdateElapsedMs <= 0) {
            return snapshot.positionMs
        }
        val sinceUpdateMs = (nowElapsedMs - snapshot.lastPositionUpdateElapsedMs).coerceAtLeast(0)
        val speed = if (snapshot.speed > 0f) snapshot.speed else 1f // some apps report 0 while playing
        return snapshot.positionMs + (sinceUpdateMs * speed).toLong()
    }
}

/**
 * Detects a change from one known track to another. The first track seen isn't a change (that's
 * joining / opening the app, handled as a late join); readings with neither ref nor title are
 * ignored; and a ref that shows up after the title (same title) is the same track, not a new one.
 */
internal class ItemKeyTracker {
    private var ref: String? = null
    private var title: String? = null
    private var known = false

    /** True when this reading is a different track from the previous known one. */
    fun changed(newRef: String?, newTitle: String?): Boolean {
        if (newRef == null && JamTracks.normalizedTitle(newTitle) == null) return false
        if (!known) {
            known = true
            ref = newRef
            title = newTitle
            return false
        }
        val same = JamTracks.sameItem(ref, title, newRef, newTitle) ?: true // can't tell: same
        // Remember the most complete reading of the track (keep a ref we already had).
        ref = if (same) newRef ?: ref else newRef
        title = newTitle ?: title.takeIf { same }
        return !same
    }

    fun changed(metadata: NowPlayingMetadata?): Boolean = changed(metadata?.playableRef, metadata?.title)

    fun changed(preview: RoomPreview): Boolean = changed(preview.playableRef, preview.title)

    fun reset() {
        ref = null
        title = null
        known = false
    }
}
