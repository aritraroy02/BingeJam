package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService

/**
 * Which media session a room syncs. Pure; one per coordinator (it remembers the room's kind).
 *
 * A room has a kind: a watch party (movie/TV apps) or a jam (music/YouTube apps). Once known it
 * never changes by itself, so a host who pauses Netflix and opens YouTube doesn't turn the
 * watch party into a YouTube jam (that would broadcast YouTube's position to every member's
 * Netflix). Only the host's explicit title choice (rooms/{code}/title) changes it. The kind is:
 *  1. the room title's service, if set;
 *  2. else the preview's service (what the host has been publishing);
 *  3. else the kind of the first app the host played in this room ([hostKindJam]).
 * Until any of these is known, the host only considers sessions that are PLAYING, so a paused app
 * left in the background can't decide the room's kind; the first one the host plays does.
 *
 * Members sync the room title's app, else the preview's app, else (host's app not known yet)
 * movie/TV apps only, so their background music can't seed a watch party's timeline.
 */
internal class SessionSelector {
    /** Kind (true = jam) of the first app the host played here; null until then. */
    private var hostKindJam: Boolean? = null

    fun reset() {
        hostKindJam = null
    }

    /** True = jam, false = watch party, null = not decided yet. */
    fun roomIsJam(room: Room?, preview: RoomPreview?): Boolean? =
        room?.title?.service?.isJam ?: preview?.service?.isJam ?: hostKindJam

    fun pick(sessions: List<MediaSessionInfo>, room: Room?, preview: RoomPreview?, isHost: Boolean): MediaSessionInfo? {
        val titleService = room?.title?.service
        if (!isHost) {
            val target = titleService ?: preview?.service
            return firstOf(sessions, if (target != null) listOf(target) else StreamingService.entries.filter { !it.isJam })
        }
        if (titleService != null) return firstOf(sessions, listOf(titleService))
        val kind = roomIsJam(room, preview)
        if (kind != null) return firstOf(sessions, StreamingService.entries.filter { it.isJam == kind })

        // Nothing decided yet: the first app the host actually plays sets the room's kind.
        val playing = sessions.filter { it.snapshot?.state == LocalPlaybackState.PLAYING }
        val picked = firstOf(playing, StreamingService.entries)
        if (picked != null) hostKindJam = StreamingService.forPackage(picked.packageName)?.isJam
        return picked
    }

    /** The first session (gateway order: most recently active first) of one of [services]. */
    private fun firstOf(sessions: List<MediaSessionInfo>, services: List<StreamingService>): MediaSessionInfo? {
        val packages = services.filter { it.isConfigured }.map { it.packageName }
        return sessions.firstOrNull { it.packageName in packages }
    }
}
