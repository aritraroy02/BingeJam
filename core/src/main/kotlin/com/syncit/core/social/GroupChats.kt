package com.syncit.core.social

import com.syncit.core.model.ChatRoomLink
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Group
import com.syncit.core.model.GroupInvite
import com.syncit.core.model.GroupInvitePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Group chats of friends, each with its own room (user's request, 2026-11-05). Only members can
 * read a group, its messages or its room link; only admins add or remove people, and only friends of
 * the admin can be added. Implemented in :data-firebase.
 */
interface GroupChats {
    /** Groups we're in, newest first by creation. */
    fun observeMyGroups(): Flow<List<Group>>

    /** Null once we're no longer a member (or it was never ours). */
    fun observeGroup(groupId: String): Flow<Group?>

    /**
     * Creates the group with us as admin; returns its id. Each of [memberUids] (friends) is added
     * straight away if their setting is "Join directly", otherwise they get an invite to accept.
     */
    suspend fun create(name: String, memberUids: Collection<String>): String

    /** Groups we've been invited to and haven't answered yet (people who chose "Ask me first"). */
    fun observeInvites(): Flow<List<GroupInvite>>

    /** Which of [uids] have an unanswered invite to [groupId] (readable by the group's members). */
    fun observeInvited(groupId: String, uids: Collection<String>): Flow<Set<String>> = flowOf(emptySet())

    suspend fun acceptInvite(groupId: String)

    suspend fun declineInvite(groupId: String)

    /** Our setting (Settings → Privacy): ask before being added to a group, or join directly. */
    fun observeInvitePolicy(): Flow<GroupInvitePolicy>

    suspend fun setInvitePolicy(policy: GroupInvitePolicy)

    suspend fun rename(groupId: String, name: String)

    /** Admins only; each uid must be one of our friends. */
    suspend fun addMembers(groupId: String, uids: Collection<String>)

    /** Admins only; never the group's creator. */
    suspend fun removeMember(groupId: String, uid: String)

    /** Admins only: make [uid] a group admin, or dismiss them as admin (never the creator). */
    suspend fun setAdmin(groupId: String, uid: String, admin: Boolean) = Unit

    /** Admins only: who can send messages and who can edit the group's info. */
    suspend fun setSettings(groupId: String, onlyAdminsSend: Boolean, onlyAdminsEdit: Boolean) = Unit

    /** Leaves the group on all our devices. */
    suspend fun leave(groupId: String)

    /** Oldest first. */
    fun observeMessages(groupId: String, limit: Int = 100): Flow<List<DirectMessage>>

    suspend fun send(groupId: String, text: String)

    /** Group id → newest message, for the Chats list. */
    fun observeLatest(): Flow<Map<String, DirectMessage>>

    /** Group id → messages from others since we last opened it. */
    fun observeUnreadCounts(): Flow<Map<String, Int>>

    suspend fun markRead(groupId: String)

    /** The group's room while anyone is in it, else null. */
    fun observeRoom(groupId: String): Flow<ChatRoomLink?>

    /** Records [code] as the group's room (everyone in the group sees "Room live · Join"). */
    suspend fun setRoom(groupId: String, code: String)

    /** We joined (true) or left (false) the group's room; keeps the "who's in" list right. */
    suspend fun setInRoom(groupId: String, inRoom: Boolean)

    /** Asks a member who isn't in the room yet to join: a notification on their phone. */
    suspend fun ping(groupId: String, uid: String)

    /** Off (fakes and tests). */
    object None : GroupChats {
        override fun observeMyGroups(): Flow<List<Group>> = flowOf(emptyList())
        override fun observeGroup(groupId: String): Flow<Group?> = flowOf(null)
        override suspend fun create(name: String, memberUids: Collection<String>): String = error("Groups aren't available")
        override fun observeInvites(): Flow<List<GroupInvite>> = flowOf(emptyList())
        override suspend fun acceptInvite(groupId: String) = Unit
        override suspend fun declineInvite(groupId: String) = Unit
        override fun observeInvitePolicy(): Flow<GroupInvitePolicy> = flowOf(GroupInvitePolicy.ASK)
        override suspend fun setInvitePolicy(policy: GroupInvitePolicy) = Unit
        override suspend fun rename(groupId: String, name: String) = Unit
        override suspend fun addMembers(groupId: String, uids: Collection<String>) = Unit
        override suspend fun removeMember(groupId: String, uid: String) = Unit
        override suspend fun leave(groupId: String) = Unit
        override fun observeMessages(groupId: String, limit: Int): Flow<List<DirectMessage>> = flowOf(emptyList())
        override suspend fun send(groupId: String, text: String) = Unit
        override fun observeLatest(): Flow<Map<String, DirectMessage>> = flowOf(emptyMap())
        override fun observeUnreadCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun markRead(groupId: String) = Unit
        override fun observeRoom(groupId: String): Flow<ChatRoomLink?> = flowOf(null)
        override suspend fun setRoom(groupId: String, code: String) = Unit
        override suspend fun setInRoom(groupId: String, inRoom: Boolean) = Unit
        override suspend fun ping(groupId: String, uid: String) = Unit
    }

    companion object {
        const val MAX_NAME_LENGTH = 50
        const val MAX_MEMBERS = 32

        /** Trimmed, inner whitespace collapsed, at most [MAX_NAME_LENGTH]; null if nothing is left. */
        fun cleanName(raw: String): String? =
            raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }
    }
}
