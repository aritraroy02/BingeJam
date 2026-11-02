package com.syncit.app.social

import com.syncit.core.model.MediaKind
import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService
import com.syncit.core.model.WatchSessionReport
import com.syncit.core.model.WatchedTogether
import java.text.Normalizer
import java.util.Locale

/**
 * "Watched Leo together · Netflix · 1h 42m" cards in DMs (user's request, 2026-11-02). Pure, so
 * the key, the together test and the wording are unit-tested (WatchTogetherTest);
 * [WatchTogetherRecorder] feeds it from the running sync session.
 *
 * Timing: durations are measured on this phone with elapsedRealtime (monotonic); only the
 * "is the friend's position report fresh" test compares server times (their reportedAt with our
 * ServerClock), never the phone's wall clock.
 */
object WatchTogether {
    /** A pair needs this long together, title playing, before a card appears (rules: ≥ 5 min). */
    const val MIN_TOGETHER_MS = 5 * 60_000L

    /** While together, the card's duration is brought up to date this often. */
    const val FLUSH_EVERY_MS = 5 * 60_000L

    /**
     * Apart for longer than this = a new session (and a new card). Same as the data layer's
     * stale-card limit (WatchCards.STALE_MS), so both sides agree on what's "the same session".
     */
    const val SESSION_GAP_MS = 2 * 60 * 60_000L

    /** How often the recorder re-checks who's together, besides on every room change. */
    const val TICK_MS = 30_000L

    /** Longest gap between two checks that still counts in full (the process may be frozen). */
    const val MAX_STEP_MS = 90_000L

    /**
     * A friend whose position reports (every 5 s while their timeline sync runs) stopped longer
     * ago than this isn't on the title any more. Members in a fallback mode never report and
     * count while online.
     */
    const val REPORT_MAX_AGE_MS = 60_000L

    /**
     * Same on every phone in the room: "w-" + room code + 8 hex digits of FNV-1a over the app and
     * the normalized title. Jams follow the host from track to track, so for them the whole jam
     * is one session (no title in the key). Don't change the recipe: phones on different
     * versions must still agree.
     */
    fun sessionKey(roomCode: String, service: StreamingService, title: String): String {
        val what = if (service.isJam) "" else normalizeTitle(title)
        return "w-$roomCode-" + "%08x".format(fnv1a32("${service.name}|$what"))
    }

    /** Lower case, punctuation and spacing runs folded (like the relay's title lookup). */
    fun normalizeTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}]+"), " ").trim()

    /** 32-bit FNV-1a over the UTF-8 bytes. */
    fun fnv1a32(text: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xff)
            hash *= 0x01000193
        }
        return hash
    }

    /** Which of our friends are watching with us right now: online in the room and still on the title. */
    fun togetherFriends(members: List<Member>, me: String, friends: Set<String>, serverNowMs: Long): Set<String> =
        members.filter { m ->
            m.uid != me && m.uid in friends && m.online &&
                (m.reportedAt?.let { serverNowMs - it <= REPORT_MAX_AGE_MS } ?: true)
        }.mapTo(HashSet()) { it.uid }

    /**
     * What counts right now, or null when nothing does: we must be on the room's title
     * ([onTitle]), the room playing (not paused), the host's preview naming a title, and at
     * least one friend together with us.
     */
    fun sample(
        onTitle: Boolean,
        room: Room?,
        preview: RoomPreview?,
        fallbackService: StreamingService?,
        me: String?,
        friends: Set<String>,
        serverNowMs: Long,
    ): WatchTogetherTracker.Now? {
        if (!onTitle || room == null || me == null || friends.isEmpty()) return null
        if (room.state?.status != PlaybackStatus.PLAYING) return null
        val title = preview?.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val service = preview.service ?: room.title?.service ?: fallbackService ?: return null
        val together = togetherFriends(room.members, me, friends, serverNowMs)
        if (together.isEmpty()) return null
        // Jams count tracks ("+ 3 more"); a movie or episode is one item.
        val item = if (service.isJam) normalizeTitle(listOfNotNull(title, preview.subtitle).joinToString(" ")) else ""
        return WatchTogetherTracker.Now(sessionKey(room.code, service, title), title, service, item, together)
    }

    /** "Watched Leo together", "Listened to Blinding Lights + 3 more together". */
    fun headline(card: WatchedTogether): String {
        val what = if (card.items > 1) "${card.title} + ${card.items - 1} more" else card.title
        return if (card.service?.kind == MediaKind.MUSIC) "Listened to $what together" else "Watched $what together"
    }

    /** "Watched Leo together · Netflix · 1h 42m". */
    fun cardText(card: WatchedTogether): String =
        listOfNotNull(headline(card), card.service?.displayName, formatDuration(card.durationMs)).joinToString(" · ")

    /** "1h 42m", "2h", "45m" (whole minutes, at least 1). */
    fun formatDuration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

/**
 * Time together per friend for the current session, from samples taken on every room change and
 * every [WatchTogether.TICK_MS]. [step] returns the card writes that are due: the first once a
 * pair reaches [minTogetherMs], then every [flushEveryMs] while together, and a last one when
 * they stop being together (paused, left, title changed, friend gone). Times are elapsedRealtime.
 * Not thread-safe: call from one thread.
 */
class WatchTogetherTracker(
    private val minTogetherMs: Long = WatchTogether.MIN_TOGETHER_MS,
    private val flushEveryMs: Long = WatchTogether.FLUSH_EVERY_MS,
    private val sessionGapMs: Long = WatchTogether.SESSION_GAP_MS,
    private val maxStepMs: Long = WatchTogether.MAX_STEP_MS,
) {
    /** One sample: the session ([sessionKey]) and the friends together with us in it. */
    data class Now(
        val sessionKey: String,
        val title: String,
        val service: StreamingService,
        /** The title/track playing now; jams count distinct ones. */
        val itemKey: String,
        val friends: Set<String>,
    )

    data class Flush(val friendUid: String, val report: WatchSessionReport)

    private class Pair(val sessionKey: String, val title: String, val service: StreamingService) {
        var togetherMs = 0L
        var flushedMs = 0L
        var together = false
        var lastTogetherAt = 0L
        val items = LinkedHashSet<String>()
    }

    private val pairs = HashMap<String, Pair>()
    private var lastStepAt: Long? = null

    fun step(nowMs: Long, now: Now?): List<Flush> {
        val out = ArrayList<Flush>()
        // The time since the last sample counts for pairs that were together then.
        val dt = lastStepAt?.let { (nowMs - it).coerceIn(0L, maxStepMs) } ?: 0L
        lastStepAt = nowMs
        for (p in pairs.values) if (p.together) p.togetherMs += dt

        val friends = now?.friends.orEmpty()
        for ((uid, p) in pairs) {
            if (p.together && (now == null || uid !in friends || p.sessionKey != now.sessionKey)) {
                p.together = false
                flush(uid, p, force = true)?.let(out::add)
            }
        }
        if (now != null) {
            for (uid in friends) {
                var p = pairs[uid]
                if (p == null || p.sessionKey != now.sessionKey || (!p.together && nowMs - p.lastTogetherAt > sessionGapMs)) {
                    p = Pair(now.sessionKey, now.title, now.service)
                    pairs[uid] = p
                }
                p.together = true
                p.lastTogetherAt = nowMs
                p.items += now.itemKey
                flush(uid, p, force = false)?.let(out::add)
            }
        }
        pairs.values.removeAll { !it.together && nowMs - it.lastTogetherAt > sessionGapMs }
        return out
    }

    /**
     * The card now says [durationMs] (the larger of both phones' measurements, or our own from
     * before the app restarted): carry on from there.
     */
    fun adopt(friendUid: String, sessionKey: String, durationMs: Long) {
        val p = pairs[friendUid]?.takeIf { it.sessionKey == sessionKey } ?: return
        if (durationMs > p.togetherMs) p.togetherMs = durationMs
        if (durationMs > p.flushedMs) p.flushedMs = durationMs
    }

    private fun flush(uid: String, p: Pair, force: Boolean): Flush? {
        if (p.togetherMs < minTogetherMs || p.togetherMs <= p.flushedMs) return null
        if (!force && p.flushedMs > 0L && p.togetherMs - p.flushedMs < flushEveryMs) return null
        p.flushedMs = p.togetherMs
        return Flush(uid, WatchSessionReport(p.sessionKey, p.title, p.service, p.togetherMs, p.items.size.coerceAtLeast(1)))
    }
}
