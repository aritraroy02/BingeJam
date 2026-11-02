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
 * [inWatchParty] is true while they're in a synced room for it. The room code itself is never
 * published (friends join only through "Ask to join", which the host approves). [startedAt] is
 * server time. [watchingWith]: the other room members' names ("Somesh and Ayan"), while
 * [inWatchParty]; display names are already visible to every signed-in user.
 */
class Activity(
    val app: ActivityApp,
    val title: String?,
    val subtitle: String?,
    val artworkJpeg: ByteArray?,
    val playing: Boolean,
    val durationMs: Long?,
    val startedAt: Long,
    val inWatchParty: Boolean,
    val watchingWith: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is Activity && app == other.app && title == other.title && subtitle == other.subtitle &&
            playing == other.playing && durationMs == other.durationMs && startedAt == other.startedAt &&
            inWatchParty == other.inWatchParty && watchingWith == other.watchingWith &&
            artworkJpeg.contentEquals(other.artworkJpeg)

    override fun hashCode(): Int =
        listOf(app, title, subtitle, playing, durationMs, startedAt, inWatchParty, watchingWith).hashCode() * 31 +
            artworkJpeg.contentHashCode()

    override fun toString(): String =
        "Activity(app=$app, title=$title, subtitle=$subtitle, playing=$playing, inWatchParty=$inWatchParty, " +
            "watchingWith=$watchingWith, art=${artworkJpeg?.size ?: 0} B)"
}

/**
 * dms/{dmId}/{id}: only the two friends read or write. [sentAt] is server time.
 *
 * [forwarded]: sent with Forward (a small "Forwarded" label). [deleted]: the sender deleted it
 * for everyone; the node stays (ordering, unread counts) but [text] is empty. [watched]: not a
 * message but a "watched together" card both friends' phones keep up to date; [fromUid] is then
 * whoever created it. Cards never count as unread and never notify.
 */
data class DirectMessage(
    val id: String,
    val fromUid: String,
    val text: String,
    val sentAt: Long,
    val forwarded: Boolean = false,
    val deleted: Boolean = false,
    val watched: WatchedTogether? = null,
)

/**
 * A "watched together" card in a DM: dms/{dmId}/w-{room}-{hash}[-n] (see [WatchSessionReport]).
 * [durationMs] is the time both were in the room with the title playing. Jams follow the host
 * from track to track, so for them [title] is the first track and [items] counts the tracks.
 */
data class WatchedTogether(
    val title: String,
    val service: StreamingService?,
    val durationMs: Long,
    val items: Int = 1,
)

/**
 * One phone's measurement of a watch session with one friend, for
 * [com.syncit.core.social.DirectMessages.recordWatchedTogether]. [sessionKey] is the same on both
 * phones (derived from the room code, app and title), so both write the same card.
 */
data class WatchSessionReport(
    val sessionKey: String,
    val title: String,
    val service: StreamingService,
    val togetherMs: Long,
    val items: Int,
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
    /**
     * Server time the host accepted or declined; null while pending. Requesters judge freshness
     * from this (not [sentAt]), so a late answer still works; hosts hide pending requests that
     * are older than a cut-off.
     */
    val answeredAt: Long? = null,
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
    /** The invitee tapped "Not now"; kept so the inviter can see it (see [SentInvite]). */
    val declined: Boolean = false,
)

/** An invite we sent (invites/{friend}/{me}), as the inviter sees it while waiting. */
data class SentInvite(
    val roomCode: String,
    val declined: Boolean,
)
