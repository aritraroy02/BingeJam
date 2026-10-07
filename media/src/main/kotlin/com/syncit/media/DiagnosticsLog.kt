package com.syncit.media

import android.util.Log
import com.syncit.core.model.MediaSessionInfo

/**
 * Logs session changes under the tag "SyncItDiag" so they can be read with
 * `adb logcat -s SyncItDiag` next to `adb shell dumpsys media_session`.
 * Logged: package, state, position, speed, actions, and the session's own metadata (title,
 * artwork size), which is what the room preview shows.
 */
internal object DiagnosticsLog {
    private const val TAG = "SyncItDiag"
    private val lastLogged = HashMap<String, String>()

    fun logSessions(sessions: List<MediaSessionInfo>) {
        val keys = sessions.map { it.sessionKey }.toSet()
        lastLogged.keys.retainAll(keys)
        for (session in sessions) {
            val line = describe(session)
            if (lastLogged[session.sessionKey] != line) {
                lastLogged[session.sessionKey] = line
                Log.d(TAG, line)
            }
        }
    }

    fun describe(session: MediaSessionInfo): String {
        val s = session.snapshot ?: return "${session.packageName} [${session.sessionKey}] no PlaybackState"
        return "${session.packageName} [${session.sessionKey}] state=${PlaybackStateMapper.stateLabel(s.rawState)} " +
            "position=${s.positionMs} lastPositionUpdateTime=${s.lastPositionUpdateElapsedMs} speed=${s.speed} " +
            "actions=0x${s.rawActions.toString(16)} ${s.actions.joinToString(",")} " +
            "title=${session.metadata?.title} subtitle=${session.metadata?.subtitle} art=${session.metadata?.artworkJpeg?.size ?: 0}B"
    }
}
