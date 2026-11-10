package com.syncit.core.social

import com.syncit.core.model.DirectMessage
import com.syncit.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Personal invite links (2026-11-10). Each account has one secret token; the link
 * FriendLinks.shareLink(username, token) sends us a friend request carrying it, and a request with
 * our current token is accepted without asking (by the relay at once, or by our app as a fallback).
 * Resetting the token makes old links ordinary friend requests.
 */
interface FriendInvites {
    /** Our token, created the first time it's asked for. */
    suspend fun myToken(): String

    /** A new token; links shared before stop adding friends automatically. */
    suspend fun resetToken(): String

    /** The person behind an invite link, or null if the username doesn't exist. */
    suspend fun lookUp(username: String): UserProfile?

    /** Accepts pending requests that carry our current token (the relay usually did already). */
    suspend fun acceptInvited()

    object None : FriendInvites {
        override suspend fun myToken(): String = error("Invite links aren't available")
        override suspend fun resetToken(): String = error("Invite links aren't available")
        override suspend fun lookUp(username: String): UserProfile? = null
        override suspend fun acceptInvited() = Unit
    }
}

enum class ReportReason(val wire: String, val label: String) {
    SPAM("spam", "Spam or scam"),
    HARASSMENT("harassment", "Harassment or bullying"),
    INAPPROPRIATE("inappropriate", "Nudity or violent content"),
    IMPERSONATION("impersonation", "Pretending to be someone else"),
    OTHER("other", "Something else"),
}

/** A group we left and blocked, so nobody can add us back or invite us to it. */
data class BlockedGroup(val id: String, val name: String)

/**
 * Report and block (2026-11-10). Reports go to the developer for review (reports/{uid}, write-once,
 * readable only in the Firebase console), with up to [MAX_EVIDENCE] recent messages from the person
 * or group, like WhatsApp. Blocking a person lives in FriendsRepository; blocking a group is here.
 */
interface Safety {
    suspend fun reportUser(uid: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean)

    suspend fun reportGroup(groupId: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean)

    /** Leaves the group and stops anyone adding us back or inviting us to it. */
    suspend fun blockGroup(groupId: String, name: String)

    suspend fun unblockGroup(groupId: String)

    fun observeBlockedGroups(): Flow<List<BlockedGroup>>

    companion object {
        const val MAX_EVIDENCE = 5
    }

    object None : Safety {
        override suspend fun reportUser(uid: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean) = Unit
        override suspend fun reportGroup(groupId: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean) = Unit
        override suspend fun blockGroup(groupId: String, name: String) = Unit
        override suspend fun unblockGroup(groupId: String) = Unit
        override fun observeBlockedGroups(): Flow<List<BlockedGroup>> = flowOf(emptyList())
    }
}
