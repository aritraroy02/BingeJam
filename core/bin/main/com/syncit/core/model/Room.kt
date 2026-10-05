package com.syncit.core.model

/** Room-level play state. [wire] is the value stored in Firebase. */
enum class PlaybackStatus(val wire: String) {
    PLAYING("playing"),
    PAUSED("paused");

    companion object {
        fun fromWire(value: String?): PlaybackStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The room's authoritative playback state: rooms/{code}/state.
 *
 * [updatedAt] is server time (ServerValue.TIMESTAMP). [seq] goes up by one on every write, so
 * a higher seq is always a later server write.
 */
data class RoomState(
    val status: PlaybackStatus,
    val positionMs: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val seq: Long,
)

/** rooms/{code}/members/{uid}. [reportedAt] and [lastSeen] are server time. */
data class Member(
    val uid: String,
    val name: String,
    val online: Boolean,
    val positionMs: Long? = null,
    val reportedAt: Long? = null,
    val lastSeen: Long? = null,
)

/** rooms/{code}/title: what the host picked to watch. */
data class RoomTitle(
    val service: StreamingService,
    val deepLink: String,
    val setBy: String,
)

/**
 * rooms/{code}/countdown: manual "3, 2, 1, play" fallback. Everyone presses play at
 * server time [playAt].
 */
data class Countdown(
    val requestedAt: Long,
    val leadMs: Long,
    val startedBy: String,
) {
    val playAt: Long get() = requestedAt + leadMs
}

data class Room(
    val code: String,
    val hostUid: String,
    val createdAt: Long,
    val title: RoomTitle?,
    val state: RoomState?,
    val members: List<Member>,
    val countdown: Countdown?,
)

/** chats/{code}/{id}. [sentAt] is server time. */
data class ChatMessage(
    val id: String,
    val uid: String,
    val name: String,
    val text: String,
    val sentAt: Long,
)

/** reactions/{code}/{id}. [sentAt] is server time. */
data class Reaction(
    val id: String,
    val uid: String,
    val name: String,
    val emoji: String,
    val sentAt: Long,
)
