package com.syncit.core.social

import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.FriendRequest
import kotlinx.coroutines.flow.Flow

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
    fun observe(friendUid: String, limit: Int = 100): Flow<List<DirectMessage>>

    suspend fun send(friendUid: String, text: String)

    /** Friend uid → number of messages since we last opened that chat (in-app badges). */
    fun observeUnreadCounts(): Flow<Map<String, Int>>

    suspend fun markRead(friendUid: String)
}
