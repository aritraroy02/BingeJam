package com.syncit.media.service

import com.syncit.core.model.MediaAction
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.RoomPreview
import com.syncit.core.session.JamFollow
import com.syncit.media.PlayableRefParser

/**
 * Member side of a jam (music/YouTube room): decides when to start the host's current track on
 * this phone, and when the member's app must be hidden from the sync engine. Pure and
 * single-threaded (the coordinator calls it on the main thread); time is passed in.
 *
 * Rules:
 *  - On the host's track (playableRef equality, else title/length match): IDLE, visible.
 *  - No session for the room's app: NEEDS_APP (the UI opens the app; following continues when its
 *    session appears). Waiting for the app doesn't use up the automatic attempt.
 *  - Otherwise start the host's track automatically, at most ONCE per host track:
 *    PLAY_FROM_URI with the host's playableRef if the app advertises it, else PLAY_FROM_SEARCH
 *    "<title> <subtitle>" if advertised. STARTING until it matches; FAILED after [startTimeoutMs].
 *  - Was on the host's track and moved off it (the member skipped, or their app reached the end of
 *    the track a moment before the host's did): wait [leftGraceMs] for the host's next track, then
 *    FAILED. We don't fight a member who chose something else; [requestRetry] ("Back to the jam")
 *    allows one more attempt.
 *  - While not on the host's track the session is hidden from the engine, so the member's other
 *    track never moves the room (same idea as the watch-party "other title" gate).
 */
internal class JamFollower(
    /** How long the app gets to switch to the host's track after we asked it to. */
    private val startTimeoutMs: Long = 6_000,
    /** How long to wait for the host's next track after the member's app left the host's track. */
    private val leftGraceMs: Long = 5_000,
) {
    sealed interface Action {
        val sessionKey: String

        data class PlayFromUri(override val sessionKey: String, val uri: String) : Action
        data class PlayFromSearch(override val sessionKey: String, val query: String) : Action
    }

    data class Decision(
        val follow: JamFollow,
        /** Hide the member's session from the engine (it isn't on the host's track). */
        val hideSession: Boolean,
        /** Not on the host's track and not trying to be: show OTHER_TITLE. */
        val otherTitle: Boolean = false,
        /** Something to ask the member's app to do now. */
        val action: Action? = null,
    )

    /** Host track we've already tried to start automatically, and when (elapsedRealtime ms). */
    private var attemptedKey: String? = null
    private var attemptAtMs = 0L
    private var attemptRejected = false

    /** Host track the member's app was last seen playing. */
    private var matchedKey: String? = null
    /** When the member's app left [matchedKey] (elapsedRealtime ms). */
    private var leftAtMs: Long? = null

    private var retryRequested = false

    /** "Back to the jam": forget the attempt for the current host track so the next update retries. */
    fun requestRetry() {
        retryRequested = true
    }

    /** The app refused the last [Decision.action] (session gone or action not advertised any more). */
    fun actionNotApplied() {
        attemptRejected = true
    }

    fun reset() {
        attemptedKey = null
        attemptAtMs = 0L
        attemptRejected = false
        matchedKey = null
        leftAtMs = null
        retryRequested = false
    }

    /**
     * [preview] is what the host is playing; [session] is this phone's session for the room's app
     * (null if that app has no session). [nowMs] is SystemClock.elapsedRealtime().
     */
    fun update(preview: RoomPreview?, session: MediaSessionInfo?, nowMs: Long): Decision {
        val service = preview?.service
        val key = preview?.let(JamTracks::key)
        // The host isn't playing anything identifiable yet: nothing to follow.
        if (service == null || key == null) return VISIBLE
        if (session == null || session.packageName != service.packageName) {
            return Decision(JamFollow.NEEDS_APP, hideSession = true)
        }

        val match = JamTracks.matches(preview, session.metadata)
        val attempted = attemptedKey == key
        // Timing: both values are elapsedRealtime, so this is the time since we asked the app.
        val startWindowOpen = attempted && !attemptRejected && nowMs - attemptAtMs < startTimeoutMs
        if (match == true) {
            matchedKey = key
            leftAtMs = null
            retryRequested = false
            return VISIBLE
        }
        if (match == null && (matchedKey == key || (attempted && !startWindowOpen))) {
            // The app publishes no title right now, so we can't check. It was on the host's track,
            // or we asked it to play it and gave it time: don't block syncing on what we can't see.
            retryRequested = false
            return VISIBLE
        }

        // Not (or not yet) on the host's track.
        if (retryRequested) {
            retryRequested = false
            attemptedKey = null
            matchedKey = null
            leftAtMs = null
        }

        if (matchedKey == key) {
            val leftAt = leftAtMs ?: nowMs.also { leftAtMs = it }
            return if (nowMs - leftAt < leftGraceMs) {
                Decision(JamFollow.IDLE, hideSession = true)
            } else {
                Decision(JamFollow.FAILED, hideSession = true, otherTitle = true)
            }
        }

        if (attemptedKey != key) {
            attemptedKey = key
            attemptAtMs = nowMs
            attemptRejected = false
            val action = chooseAction(preview, session)
                ?: return Decision(JamFollow.FAILED, hideSession = true, otherTitle = true).also { attemptRejected = true }
            return Decision(JamFollow.STARTING, hideSession = true, action = action)
        }

        return if (startWindowOpen) {
            Decision(JamFollow.STARTING, hideSession = true)
        } else {
            Decision(JamFollow.FAILED, hideSession = true, otherTitle = true)
        }
    }

    /** PLAY_FROM_URI with the exact track if possible, else PLAY_FROM_SEARCH, else nothing. */
    private fun chooseAction(preview: RoomPreview, session: MediaSessionInfo): Action? {
        val actions = session.snapshot?.actions.orEmpty()
        // Re-validate the host's ref (it came over the network) against this app's public format.
        val ref = PlayableRefParser.validFor(session.packageName, preview.playableRef)
        if (ref != null && MediaAction.PLAY_FROM_URI in actions) return Action.PlayFromUri(session.sessionKey, ref)
        val query = JamTracks.searchQuery(preview)
        if (query != null && MediaAction.PLAY_FROM_SEARCH in actions) return Action.PlayFromSearch(session.sessionKey, query)
        return null
    }

    private companion object {
        val VISIBLE = Decision(JamFollow.IDLE, hideSession = false)
    }
}
