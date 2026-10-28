package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService

/**
 * Which media session a room syncs. Pure; one per coordinator (it remembers the room's kind).
 *
 * A room has a kind: a watch party (movie/TV apps) or a jam (music/YouTube apps). Rooms are
 * created without a title, so the host's apps decide it:
 *  - While the host is alone (no other member has joined), the kind isn't locked: it follows the
 *    app the host most recently started playing. Spotify already playing when the host opens the
 *    room doesn't make it a jam for good; pausing it and playing Netflix makes it a watch party.
 *    When nothing is playing, the last kind (or the preview's) is kept.
 *  - Once another member has joined, the kind is locked: a host who pauses Netflix and opens
 *    YouTube doesn't turn the watch party into a YouTube jam (that would broadcast YouTube's
 *    position to every member's Netflix). Switching apps of the same kind is allowed.
 *  - The room title (rooms/{code}/title), set explicitly by the host, always decides.
 *
 * Members sync the room title's app, else the preview's app, else (host's app not known yet)
 * movie/TV apps only, so their background music can't seed a watch party's timeline.
 */
internal class SessionSelector {
    /** Kind (true = jam) of the app the host last played here; null until then. */
    private var hostKindJam: Boolean? = null

    /** Another member has joined: the kind no longer follows the host's apps. */
    private var locked = false

    fun reset() {
        hostKindJam = null
        locked = false
    }

    /** True = jam, false = watch party, null = not decided yet. Host's view. */
    fun roomIsJam(room: Room?, preview: RoomPreview?): Boolean? =
        room?.title?.service?.isJam ?: hostKindJam ?: preview?.service?.isJam

    fun pick(sessions: List<MediaSessionInfo>, room: Room?, preview: RoomPreview?, isHost: Boolean): MediaSessionInfo? {
        val titleService = room?.title?.service
        if (!isHost) {
            val target = titleService ?: preview?.service
            return firstOf(sessions, if (target != null) listOf(target) else StreamingService.entries.filter { !it.isJam })
        }
        if (titleService != null) return firstOf(sessions, listOf(titleService))

        // Sticky: once someone has joined, the room is what they joined.
        if (room != null && room.members.any { it.uid != room.hostUid }) locked = true
        val kind = roomIsJam(room, preview)
        if (!locked || kind == null) {
            // Alone (or nobody knows the kind yet, e.g. a friend joined before the host played
            // anything): the app the host most recently started playing (gateway order) decides.
            val playing = firstOf(sessions.filter { it.snapshot?.state == LocalPlaybackState.PLAYING }, StreamingService.entries)
            if (playing != null) {
                hostKindJam = StreamingService.forPackage(playing.packageName)?.isJam
                return playing
            }
        }
        // Locked, or nothing playing: stay with the room's kind. Unknown: wait for the host to play.
        if (kind == null) return null
        return firstOf(sessions, StreamingService.entries.filter { it.isJam == kind })
    }

    /** The first session (gateway order: most recently active first) of one of [services]. */
    private fun firstOf(sessions: List<MediaSessionInfo>, services: List<StreamingService>): MediaSessionInfo? {
        val packages = services.filter { it.isConfigured }.map { it.packageName }
        return sessions.firstOrNull { it.packageName in packages }
    }
}
