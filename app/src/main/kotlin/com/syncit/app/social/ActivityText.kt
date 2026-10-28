package com.syncit.app.social

import com.syncit.core.model.Activity
import com.syncit.core.model.MediaKind
import com.syncit.core.model.Presence

/**
 * The status line under a friend's name: what they're playing, "Online", or "Last seen 5m ago".
 * Times are server time ([nowMs] = the transport's server clock, same base as Presence.lastSeen).
 */
object ActivityText {

    /** What to show for a friend. Activity only counts while they're online (it may be stale otherwise). */
    fun statusLine(presence: Presence?, nowMs: Long): String {
        if (presence == null) return "Offline"
        val activity = presence.activity
        return when {
            presence.online && activity != null -> activityLine(activity)
            presence.online -> "Online"
            else -> lastSeen(presence.lastSeen, nowMs)
        }
    }

    /** The activity to show, or null (offline friends' activity may be stale). */
    fun visibleActivity(presence: Presence?): Activity? = presence?.takeIf { it.online }?.activity

    /**
     * "Watching S2:E4 · Redanian Intelligence on Netflix" (Netflix puts the episode name in the
     * title and "S2:E4" in the subtitle), "Listening to Blinding Lights on Spotify",
     * "Watching Lo-fi beats on YouTube". Paused activity gets " (paused)".
     */
    fun activityLine(activity: Activity): String {
        val app = activity.app.displayName
        val title = activity.title?.trim()?.takeIf { it.isNotEmpty() }
        val subtitle = activity.subtitle?.trim()?.takeIf { it.isNotEmpty() }
        val line = when (activity.app.kind) {
            MediaKind.MOVIE_TV -> {
                val what = listOfNotNull(subtitle, title).joinToString(" · ")
                if (what.isEmpty()) "Watching $app" else "Watching $what on $app"
            }
            MediaKind.VIDEO -> if (title == null) "Watching $app" else "Watching $title on $app"
            MediaKind.MUSIC -> if (title == null) "Listening on $app" else "Listening to $title on $app"
        }
        return if (activity.playing) line else "$line (paused)"
    }

    /**
     * "In a watch party with Somesh" / "In a jam" while they're syncing a room for this title. The
     * room code itself is never published.
     */
    fun watchPartyLabel(activity: Activity?): String? {
        if (activity?.inWatchParty != true) return null
        val label = RoomWording.inRoomLabel(RoomWording.isJam(activity.app))
        val with = activity.watchingWith?.trim()?.takeIf { it.isNotEmpty() } ?: return label
        return "$label with $with"
    }

    /**
     * Others in our room, worded for friends: "Somesh", "Somesh and Ayan", "Somesh, Ayan and 2
     * others". Null when nobody else is there.
     */
    fun watchingWith(names: List<String>): String? {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when (clean.size) {
            0 -> null
            1 -> clean[0]
            2 -> "${clean[0]} and ${clean[1]}"
            3 -> "${clean[0]}, ${clean[1]} and ${clean[2]}"
            else -> "${clean[0]}, ${clean[1]} and ${clean.size - 2} others"
        }?.take(100)
    }

    fun lastSeen(lastSeen: Long?, nowMs: Long): String {
        if (lastSeen == null || lastSeen <= 0) return "Offline"
        // Clock estimates can put lastSeen slightly in the future; treat that as "just now".
        val minutes = (nowMs - lastSeen).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "Last seen just now"
            minutes < 60 -> "Last seen ${minutes}m ago"
            minutes < 24 * 60 -> "Last seen ${minutes / 60}h ago"
            minutes < 7 * 24 * 60 -> "Last seen ${minutes / (24 * 60)}d ago"
            else -> "Last seen over a week ago"
        }
    }

    /** What to search for in the friend's app: song + artist for music, the title otherwise. */
    fun searchQuery(activity: Activity): String? {
        val title = activity.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val subtitle = activity.subtitle?.trim()?.takeIf { it.isNotEmpty() }
        return if (activity.app.kind == MediaKind.MUSIC && subtitle != null) "$title $subtitle" else title
    }
}
