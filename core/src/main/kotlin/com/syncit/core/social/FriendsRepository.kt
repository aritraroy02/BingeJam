package com.syncit.core.social

import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.FriendRequest
import com.syncit.core.model.WatchSessionReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Friend requests and friendships. friends/{a}/{b} and friends/{b}/{a} are written together on
 * accept, so the relation is always mutual. Chat and activity are friends-only.
 * Implemented in :data-firebase.
 */
interface FriendsRepository {
    /** Friends with their profile and live presence/activity. */
    fun observeFriends(): Flow<List<Friend>>

    fun observeIncomingRequests(): Flow<List<FriendRequest>>

    fun observeOutgoingRequests(): Flow<List<FriendRequest>>

    /** Throws [FriendRequestException] (unknown user, yourself, already friends, blocked). */
    suspend fun sendRequest(toUsername: String)

    /** If they had already sent us a request, sending one back accepts it instead. */
    suspend fun accept(fromUid: String)

    suspend fun decline(fromUid: String)

    suspend fun cancel(toUid: String)

    suspend fun unfriend(uid: String)

    /** Removes the friendship and stops them sending requests or messages. */
    suspend fun block(uid: String)

    suspend fun unblock(uid: String)

    fun observeBlocked(): Flow<Set<String>>
}

class FriendRequestException(message: String) : Exception(message)

/** One-to-one chat between friends. dmId = the two uids sorted and joined with "_". */
interface DirectMessages {
    /** Oldest first, without the ones we deleted for ourselves ([deleteForMe]). */
    fun observe(friendUid: String, limit: Int = 100): Flow<List<DirectMessage>>

    /** [forwarded]: sent with Forward, shown with a "Forwarded" label. */
    suspend fun send(friendUid: String, text: String, forwarded: Boolean = false)

    /**
     * Our own message only: clears its text and marks it deleted for both of us (the node stays,
     * so order and unread counts don't change). Can't be undone.
     */
    suspend fun deleteForEveryone(friendUid: String, messageId: String)

    /** Hides a message (or card) for us only, on all our devices; the friend still sees it. */
    suspend fun deleteForMe(friendUid: String, messageId: String)

    /**
     * Creates or extends the "watched together" card for [report]'s session in our chat with
     * [friendUid]. Both phones call this for the same session and end up on the same card; its
     * duration only grows (the larger of the two measurements). No notification, not unread.
     * Returns the card's duration after the write, or null if nothing was written.
     */
    suspend fun recordWatchedTogether(friendUid: String, report: WatchSessionReport): Long?

    /**
     * Friend uid → the newest message in our chat with them (cards included, "deleted for me" left
     * out), for the Chats list. Friends we've never messaged are missing.
     */
    fun observeLatest(): Flow<Map<String, DirectMessage>> = flowOf(emptyMap())

    /** Friend uid → number of messages since we last opened that chat (in-app badges). */
    fun observeUnreadCounts(): Flow<Map<String, Int>>

    suspend fun markRead(friendUid: String)

    /**
     * Typing indicator, WhatsApp-style (user's request, 2026-10-29). Call with true as the user
     * types (implementations throttle the writes) and false when the box is cleared, the message
     * is sent or the chat closes. Short-lived: stored with a server timestamp, removed on disconnect.
     */
    suspend fun setTyping(friendUid: String, typing: Boolean)

    /** True while [friendUid] is typing in our chat (their entry is only a few seconds old, in server time). */
    fun observeTyping(friendUid: String): Flow<Boolean>
}
