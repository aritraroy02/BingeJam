package com.syncit.media.activity

import com.syncit.core.model.ActivityApp
import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.social.ActivityUpdate

/**
 * Pure decision logic for [AndroidActivityTracker]: which media session is "what I'm playing",
 * and when to publish or clear it. No Android, no coroutines; the caller passes the time.
 *
 * Time: every `nowMs` is local monotonic time (SystemClock.elapsedRealtime() in production, a
 * fake clock in tests). Only differences between two `nowMs` values are used, never wall time.
 *
 * Rules:
 * - Only sessions whose package is an [ActivityApp] the user shares count.
 * - A session counts if it's playing, or paused and this app played within [idleTimeoutMs].
 *   Paused sessions we never saw play (e.g. Spotify left paused for hours) don't count, and
 *   neither do idle/stopped ones (YouTube's muted home-feed previews publish metadata with
 *   state NONE).
 * - Playing beats paused; then the session whose title/playing state changed most recently;
 *   then the gateway's order (most recently active first).
 * - If the session goes away (Netflix closes it when leaving the player), the last activity is
 *   kept as paused until [idleTimeoutMs] after it last played, so it doesn't flicker off.
 * - Publish only when the app, title, subtitle, playing flag, watch-party flag or artwork presence
 *   changes (never on position ticks), and only once it has been stable for [debounceMs].
 *   Clearing because the app is no longer shared is immediate.
 * - The first decision after start is always published (even null), clearing stale activity
 *   left by a previous run.
 */
internal class ActivityDecider(
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val retryMs: Long = RETRY_MS,
) {
    private class Seen(var playing: Boolean, var title: String?, var changedAtMs: Long, val app: ActivityApp)

    private class Shown(val app: ActivityApp, val metadata: NowPlayingMetadata?)

    /** What identifies an activity for "did it change?"; deliberately excludes position. */
    private data class Key(
        val app: ActivityApp,
        val title: String?,
        val subtitle: String?,
        val playing: Boolean,
        val inWatchParty: Boolean,
        val hasArtwork: Boolean,
    )

    /** Per session key: last seen playing flag and title, and when either last changed. */
    private val seen = HashMap<String, Seen>()

    /** Per app: local time it was last seen playing. */
    private val lastPlayedAtMs = HashMap<ActivityApp, Long>()

    private var lastShown: Shown? = null
    private var shared: Set<ActivityApp> = emptySet()

    /** What should be shared right now (not debounced). */
    var desired: ActivityUpdate? = null
        private set
    private var desiredKey: Key? = null
    private var desiredSinceMs = 0L

    private var hasPublished = false
    private var publishedKey: Key? = null
    private var publishedApp: ActivityApp? = null
    private var retryAtMs = Long.MIN_VALUE

    /** Something the caller should publish now; [update] null means "clear". */
    class Due(val update: ActivityUpdate?)

    fun update(sessions: List<MediaSessionInfo>, sharedApps: Set<ActivityApp>, inWatchParty: Boolean, nowMs: Long) {
        shared = sharedApps
        val present = HashSet<String>()
        val candidates = ArrayList<Pair<MediaSessionInfo, Seen>>()

        for (session in sessions) {
            val app = ActivityApp.forPackage(session.packageName) ?: continue
            present += session.sessionKey
            val state = session.snapshot?.state
            val playing = state in PLAYING_STATES
            val title = session.metadata?.title
            val s = seen[session.sessionKey]
            if (s == null) {
                seen[session.sessionKey] = Seen(playing, title, nowMs, app)
            } else {
                // It played until this moment.
                if (s.playing && !playing) lastPlayedAtMs[app] = nowMs
                if (s.playing != playing || s.title != title) s.changedAtMs = nowMs
                s.playing = playing
                s.title = title
            }
            if (playing) lastPlayedAtMs[app] = nowMs
            if (app !in sharedApps) continue
            if (playing || (state == LocalPlaybackState.PAUSED && playedRecently(app, nowMs))) {
                candidates += session to seen.getValue(session.sessionKey)
            }
        }

        // Sessions that went away: if one was playing, it played until now.
        val gone = seen.keys - present
        for (key in gone) {
            val s = seen.remove(key) ?: continue
            if (s.playing) lastPlayedAtMs[s.app] = nowMs
        }

        // Playing first, then most recently changed. sortedWith is stable, so ties keep the
        // gateway's order (most recently active first).
        val best = candidates.sortedWith(
            compareByDescending<Pair<MediaSessionInfo, Seen>> { it.second.playing }
                .thenByDescending { it.second.changedAtMs },
        ).firstOrNull()

        val next: ActivityUpdate? = if (best != null) {
            val (session, s) = best
            lastShown = Shown(s.app, session.metadata)
            // Only apps with a room type (watch party or jam, ActivityApp.joinable) count as "in a
            // room"; never the room code itself.
            ActivityUpdate(s.app, session.metadata, s.playing, inWatchParty && s.app.joinable)
        } else {
            val last = lastShown
            if (last != null && last.app in sharedApps && playedRecently(last.app, nowMs)) {
                ActivityUpdate(last.app, last.metadata, false, inWatchParty && last.app.joinable)
            } else {
                lastShown = null
                null
            }
        }

        val key = next?.toKey()
        if (key != desiredKey || (next == null) != (desired == null)) desiredSinceMs = nowMs
        desired = next
        desiredKey = key
    }

    /** What to publish now, or null if nothing is due. */
    fun due(nowMs: Long): Due? {
        if (hasPublished && desiredKey == publishedKey) return null
        if (nowMs < retryAtMs) return null
        val app = publishedApp
        // Sharing turned off for what's on show: clear without waiting.
        if (desired == null && app != null && app !in shared) return Due(null)
        // Elapsed local time since the decision last changed.
        return if (nowMs - desiredSinceMs >= debounceMs) Due(desired) else null
    }

    fun onPublished(update: ActivityUpdate?) {
        hasPublished = true
        publishedKey = update?.toKey()
        publishedApp = update?.app
        retryAtMs = Long.MIN_VALUE
    }

    fun onPublishFailed(nowMs: Long) {
        retryAtMs = nowMs + retryMs
    }

    /** True if something non-null was published and not cleared since. */
    val hasPublishedActivity: Boolean get() = publishedApp != null

    /** How long the caller can wait before calling [update]/[due] again with no new input. */
    fun nextCheckInMs(nowMs: Long): Long {
        var wait = MAX_WAIT_MS
        if (!hasPublished || desiredKey != publishedKey) {
            // Debounce or retry deadline, whichever is later.
            val at = maxOf(desiredSinceMs + debounceMs, retryAtMs)
            wait = minOf(wait, at - nowMs)
        }
        val d = desired
        if (d != null && !d.playing) {
            // When the paused/gone activity expires.
            val playedAt = lastPlayedAtMs[d.app]
            if (playedAt != null) wait = minOf(wait, playedAt + idleTimeoutMs - nowMs)
        }
        return wait.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
    }

    private fun playedRecently(app: ActivityApp, nowMs: Long): Boolean {
        val at = lastPlayedAtMs[app] ?: return false
        return nowMs - at < idleTimeoutMs
    }

    private fun ActivityUpdate.toKey() = Key(
        app = app,
        title = metadata?.title,
        subtitle = metadata?.subtitle,
        playing = playing,
        inWatchParty = inWatchParty,
        hasArtwork = metadata?.artworkJpeg != null,
    )

    companion object {
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val DEBOUNCE_MS = 2_000L
        const val RETRY_MS = 30_000L
        private const val MIN_WAIT_MS = 50L
        private const val MAX_WAIT_MS = 60_000L

        private val PLAYING_STATES = setOf(
            LocalPlaybackState.PLAYING,
            LocalPlaybackState.BUFFERING,
            LocalPlaybackState.FAST_FORWARDING,
            LocalPlaybackState.REWINDING,
        )
    }
}
