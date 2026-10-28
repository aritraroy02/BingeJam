package com.syncit.app.ui.room

import com.syncit.core.config.SyncStatusLabels
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.JamFollow
import java.net.URI

/**
 * What the room hero offers a member who isn't on the host's title/track. Pure Kotlin so the
 * decision is unit-tested (MemberPromptTest); RoomScreen draws it.
 */
internal enum class MemberPrompt {
    NONE,

    /** "Play in <app>": open the app (jams: on the host's exact track when we have its ref). */
    OPEN_APP,

    /** Jams: the app ignored our request or the member wandered off; "Back to the jam" = followHost(). */
    BACK_TO_JAM,

    /** Jams: we've asked the member's app to play the host's track; just a hint. */
    STARTING,
}

internal object MemberPrompts {
    /** Watch parties: states where a member isn't on the host's title yet. */
    private val WATCH_PARTY_PLAY = setOf(SyncStatus.APP_NOT_OPEN, SyncStatus.SESSION_LOST, SyncStatus.OTHER_TITLE)

    fun of(isHost: Boolean, service: StreamingService?, status: SyncStatus, jamFollow: JamFollow): MemberPrompt {
        if (isHost || service == null) return MemberPrompt.NONE
        if (!service.isJam) return if (status in WATCH_PARTY_PLAY) MemberPrompt.OPEN_APP else MemberPrompt.NONE
        return when {
            jamFollow == JamFollow.STARTING -> MemberPrompt.STARTING
            // No media session yet: only the UI can open the app (a background service can't).
            jamFollow == JamFollow.NEEDS_APP -> MemberPrompt.OPEN_APP
            jamFollow == JamFollow.FAILED || status == SyncStatus.OTHER_TITLE -> MemberPrompt.BACK_TO_JAM
            status == SyncStatus.APP_NOT_OPEN || status == SyncStatus.SESSION_LOST -> MemberPrompt.OPEN_APP
            else -> MemberPrompt.NONE
        }
    }

    /** SyncStatusLabels, except jams talk about tracks rather than titles/episodes. */
    fun statusLabel(status: SyncStatus, service: StreamingService?): String =
        if (status == SyncStatus.OTHER_TITLE && service?.isJam == true) {
            "On a different track"
        } else {
            SyncStatusLabels.label(status, service?.displayName ?: "your streaming app")
        }

    /**
     * The host's playable ref, if it's safe to hand to [service]'s app: `spotify:` URIs for
     * Spotify, or an https link on one of the service's own hosts (youtube.com/watch?v=…,
     * music.youtube.com/watch?v=…). The ref comes from another user via the room preview, so
     * anything else (other schemes, other hosts, junk) is dropped; the caller falls back to
     * opening the app. The intent is also pinned to the service's package.
     */
    fun safePlayableRef(service: StreamingService, ref: String?): String? {
        val trimmed = ref?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REF_LENGTH } ?: return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "spotify" -> trimmed.takeIf { service == StreamingService.SPOTIFY }
            "https" -> {
                val host = uri.host?.lowercase() ?: return null
                trimmed.takeIf { service.linkHosts.any { h -> host == h || host.endsWith(".$h") } }
            }
            else -> null
        }
    }

    private const val MAX_REF_LENGTH = 500
}

/**
 * Jams: open the member's app by itself (the coordinator's decision, 2026-10-15), because a
 * background service can't launch apps and NEEDS_APP means nothing will play until something
 * does. At most once per [key] (room code + the host's track), so it never loops and doesn't fire
 * again when the user comes back to the room for the same track. The "Play in <app>" button stays
 * as the manual path. Never for watch parties.
 */
internal object JamAutoOpen {

    /** Room code + the host's playableRef, else its title; null when there's nothing specific to open. */
    fun key(roomCode: String, playableRef: String?, title: String?): String? {
        val what = playableRef?.trim()?.takeIf { it.isNotEmpty() } ?: title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "$roomCode|$what"
    }

    fun shouldOpen(
        resumed: Boolean,
        isHost: Boolean,
        service: StreamingService?,
        jamFollow: JamFollow,
        key: String?,
        alreadyOpened: Set<String>,
    ): Boolean =
        resumed && !isHost && service?.isJam == true && jamFollow == JamFollow.NEEDS_APP &&
            key != null && key !in alreadyOpened
}
