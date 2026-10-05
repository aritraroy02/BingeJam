package com.syncit.core.config

import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus

/** User-facing text for sync status, shared by the room screen and the service notification. */
object SyncStatusLabels {
    fun label(status: SyncStatus, appName: String): String = when (status) {
        SyncStatus.IDLE -> "Not in a room"
        SyncStatus.WAITING_FOR_ROOM -> "Connecting to room…"
        SyncStatus.NO_NOTIFICATION_ACCESS -> "Session not found: turn on Notification Access"
        SyncStatus.NOT_CONFIGURED -> "$appName isn't configured yet"
        SyncStatus.APP_NOT_OPEN -> "$appName not open"
        SyncStatus.SESSION_LOST -> "Session not found"
        SyncStatus.SYNCING -> "Syncing"
        SyncStatus.CORRECTING -> "Out of sync, correcting"
        SyncStatus.CATCHING_UP -> "Paused briefly so others can catch up"
    }

    fun modeNotice(mode: SyncMode?, appName: String): String? = when (mode) {
        SyncMode.PLAY_PAUSE_CATCH_UP ->
            "$appName doesn't support seeking from other apps. Play and pause are synced; if you drift ahead, you'll be paused briefly so others catch up."
        SyncMode.PLAY_PAUSE_ONLY ->
            "$appName doesn't report its position, so only play and pause are synced. Scrubbing won't sync."
        SyncMode.MANUAL_COUNTDOWN ->
            "Can't control $appName from here. Use the 3, 2, 1 countdown and press play together."
        SyncMode.FULL, null -> null
    }
}
