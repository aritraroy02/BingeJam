package com.syncit.core.model

/**
 * Public profile: users/{uid}. Readable by any signed-in user (so people can be found by
 * username); only the owner writes it. [uid] is the Firebase user ID, the internal unique code.
 * [username] is the unique, editable handle (lowercase a–z, 0–9, '_' and '.', 3–20 chars, not
 * starting or ending with '.'). Times are server time.
 */
data class UserProfile(
    val uid: String,
    val username: String,
    val displayName: String,
    val photoUrl: String? = null,
    val bio: String? = null,
    val createdAt: Long = 0L,
)

/** Who is signed in. [email] is private (users_private/{uid}) and never shown to others. */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(
        val uid: String,
        val email: String?,
        val displayName: String?,
        val photoUrl: String?,
        val provider: SignInProvider,
    ) : AuthState
}

enum class SignInProvider { GOOGLE, EMAIL_LINK, OTHER }

data class FriendRequest(
    val fromUid: String,
    val toUid: String,
    /** Snapshots of both profiles at send time, for display without extra reads. */
    val fromUsername: String,
    val fromDisplayName: String,
    val toUsername: String,
    val toDisplayName: String,
    val sentAt: Long,
)

data class Friend(
    val profile: UserProfile,
    val since: Long,
    val presence: Presence?,
)

/** presence/{uid}: readable by the user's friends only. [lastSeen] is server time. */
data class Presence(
    val online: Boolean,
    val lastSeen: Long?,
    val activity: Activity?,
)

/**
 * What a user is playing, published only for apps they chose to share. Built from the app's
 * MediaSession metadata, never notifications. [artworkJpeg] is a small thumbnail (≤ 52 KB).
 * [roomCode] is set while they host a room for it. [startedAt] is server time.
 */
class Activity(
    val app: ActivityApp,
    val title: String?,
    val subtitle: String?,
    val artworkJpeg: ByteArray?,
    val playing: Boolean,
    val durationMs: Long?,
    val startedAt: Long,
    val roomCode: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is Activity && app == other.app && title == other.title && subtitle == other.subtitle &&
            playing == other.playing && durationMs == other.durationMs && startedAt == other.startedAt &&
            roomCode == other.roomCode && artworkJpeg.contentEquals(other.artworkJpeg)

    override fun hashCode(): Int =
        listOf(app, title, subtitle, playing, durationMs, startedAt, roomCode).hashCode() * 31 +
            artworkJpeg.contentHashCode()

    override fun toString(): String =
        "Activity(app=$app, title=$title, subtitle=$subtitle, playing=$playing, roomCode=$roomCode, art=${artworkJpeg?.size ?: 0} B)"
}

/** dms/{dmId}/{id}: only the two friends read or write. [sentAt] is server time. */
data class DirectMessage(
    val id: String,
    val fromUid: String,
    val text: String,
    val sentAt: Long,
)

/** A friend asked to join what you're watching: joinRequests/{hostUid}/{fromUid}. */
data class JoinRequest(
    val hostUid: String,
    val fromUid: String,
    val fromUsername: String,
    val fromDisplayName: String,
    /** What they saw the host watching when they asked. */
    val app: ActivityApp,
    val title: String?,
    val status: JoinStatus,
    /** Set by the host when accepting. */
    val roomCode: String?,
    val sentAt: Long,
)

enum class JoinStatus { PENDING, ACCEPTED, DECLINED }

/** A friend invited you into their room: invites/{toUid}/{fromUid}. */
data class RoomInvite(
    val fromUid: String,
    val fromUsername: String,
    val fromDisplayName: String,
    val roomCode: String,
    val app: ActivityApp?,
    val title: String?,
    val sentAt: Long,
)
