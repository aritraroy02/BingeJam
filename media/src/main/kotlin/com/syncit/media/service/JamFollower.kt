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
 *    If the app advertises neither, NEEDS_APP: the UI opens the app on the exact item instead.
 *  - Was on the host's track and moved off it: wait [leftGraceMs] for the host's next track (the
 *    member's app may simply have reached the end a moment before the host's). If none comes, the
 *    member chose something else: FAILED and opted out, with no automatic attempts on later host
 *    tracks either, until [requestRetry] ("Back to the jam") or they return to the host's track.
 *  - While not on the host's track the session is hidden from the engine, so the member's other
 *    track never moves the room (same idea as the watch-party "other title" gate).
 *
 * "Host track" = an [ItemKeyTracker] generation: a playableRef arriving after the title is the
 * same track, so it doesn't trigger a second attempt.
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

    private val hostTrack = ItemKeyTracker()
    /** Generation of the host's current track; goes up on every host track change. */
    private var item = 0

    /** Host track we've already tried to start automatically, and when (elapsedRealtime ms). */
    private var attemptedItem: Int? = null
    private var attemptAtMs = 0L
    private var attemptRejected = false
    /** Host track the member's app can't be told to play (no PLAY_FROM_URI/SEARCH): NEEDS_APP. */
    private var noRemoteStartItem: Int? = null

    /** Host track the member's app was last seen playing. */
    private var matchedItem: Int? = null
    /** When the member's app left [matchedItem] (elapsedRealtime ms). */
    private var leftAtMs: Long? = null
    /** The member deliberately left the jam's track: no automatic attempts until they opt back in. */
    private var optedOut = false

    private var retryRequested = false

    /** "Back to the jam": opt back in and try the current host track again on the next update. */
    fun requestRetry() {
        retryRequested = true
    }

    /** The app refused the last [Decision.action] (session gone or action not advertised any more). */
    fun actionNotApplied() {
        attemptRejected = true
    }

    fun reset() {
        hostTrack.reset()
        item = 0
        attemptedItem = null
        attemptAtMs = 0L
        attemptRejected = false
        noRemoteStartItem = null
        matchedItem = null
        leftAtMs = null
        optedOut = false
        retryRequested = false
    }

    /**
     * [preview] is what the host is playing; [session] is this phone's session for the room's app
     * (null if that app has no session). [nowMs] is SystemClock.elapsedRealtime().
     */
    fun update(preview: RoomPreview?, session: MediaSessionInfo?, nowMs: Long): Decision {
        val service = preview?.service
        // The host isn't playing anything identifiable yet: nothing to follow.
        if (service == null || JamTracks.key(preview) == null) return VISIBLE
        if (hostTrack.changed(preview)) item++
        if (session == null || session.packageName != service.packageName) {
            return Decision(JamFollow.NEEDS_APP, hideSession = true)
        }

        val match = JamTracks.matches(preview, session.metadata)
        val attempted = attemptedItem == item
        // Timing: both values are elapsedRealtime, so this is the time since we asked the app.
        val startWindowOpen = attempted && !attemptRejected && nowMs - attemptAtMs < startTimeoutMs
        if (match == true) {
            matchedItem = item
            leftAtMs = null
            optedOut = false
            retryRequested = false
            return VISIBLE
        }
        if (match == null && (matchedItem == item || (attempted && !startWindowOpen))) {
            // The app publishes no title right now, so we can't check. It was on the host's track,
            // or we asked it to play it and gave it time: don't block syncing on what we can't see.
            retryRequested = false
            return VISIBLE
        }

        // Not (or not yet) on the host's track.
        if (retryRequested) {
            retryRequested = false
            attemptedItem = null
            noRemoteStartItem = null
            matchedItem = null
            leftAtMs = null
            optedOut = false
        }
        if (optedOut) return FAILED

        if (matchedItem == item) {
            val leftAt = leftAtMs ?: nowMs.also { leftAtMs = it }
            if (nowMs - leftAt < leftGraceMs) return Decision(JamFollow.IDLE, hideSession = true)
            optedOut = true
            return FAILED
        }

        if (attemptedItem != item) {
            attemptedItem = item
            attemptAtMs = nowMs
            attemptRejected = false
            val action = chooseAction(preview, session)
            if (action == null) {
                // The app can't be told what to play: the UI opens it on the exact item.
                noRemoteStartItem = item
                attemptRejected = true
                return Decision(JamFollow.NEEDS_APP, hideSession = true)
            }
            return Decision(JamFollow.STARTING, hideSession = true, action = action)
        }
        if (noRemoteStartItem == item) return Decision(JamFollow.NEEDS_APP, hideSession = true)

        return if (startWindowOpen) Decision(JamFollow.STARTING, hideSession = true) else FAILED
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
        val FAILED = Decision(JamFollow.FAILED, hideSession = true, otherTitle = true)
    }
}
