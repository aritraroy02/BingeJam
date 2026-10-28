package com.syncit.core.media

import com.syncit.core.model.MediaSessionInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads and controls other apps' MediaSessions. Implemented in :media on top of
 * MediaSessionManager + MediaController; requires Notification Access.
 */
interface MediaControllerGateway {
    /** True once the user has enabled our notification listener in system settings. */
    val notificationAccessGranted: StateFlow<Boolean>

    /** Every active media session on the device, most recently active first. Updates live. */
    val sessions: StateFlow<List<MediaSessionInfo>>

    /** Re-check access and re-read sessions (e.g. when returning from the settings screen). */
    fun refresh()

    /** Each returns false if the session is gone. Supported actions are in the session's snapshot. */
    fun play(sessionKey: String): Boolean
    fun pause(sessionKey: String): Boolean
    fun seekTo(sessionKey: String, positionMs: Long): Boolean

    /**
     * Jams: start a specific item through the session's public transport controls. Only call when
     * the snapshot advertises PLAY_FROM_URI / PLAY_FROM_SEARCH. Return false if the session is gone.
     */
    fun playFromUri(sessionKey: String, uri: String): Boolean

    fun playFromSearch(sessionKey: String, query: String): Boolean
}
