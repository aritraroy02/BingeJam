package com.syncit.core.model

/** One person in a group chat. Admins can add and remove people; anyone can rename the group. */
data class GroupMember(val uid: String, val admin: Boolean, val joinedAt: Long)

/**
 * A group chat of friends (user's request, 2026-11-05): groups/{id} {name, createdBy, createdAt,
 * members/{uid} {role, joinedAt}}. Every group has its own room, see [ChatRoomLink].
 */
data class Group(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: Long,
    val members: List<GroupMember>,
) {
    val memberUids: Set<String> get() = members.mapTo(HashSet()) { it.uid }

    fun isAdmin(uid: String?): Boolean = uid != null && members.any { it.uid == uid && it.admin }

    fun isMember(uid: String?): Boolean = uid != null && members.any { it.uid == uid }
}

/** An invite to a group, for people whose setting is "Ask me first": groupInvites/{to}/{groupId}. */
data class GroupInvite(
    val groupId: String,
    val groupName: String,
    val fromUid: String,
    val fromName: String,
    val sentAt: Long,
)

/**
 * Settings → Privacy (user's request, 2026-11-05): whether friends can add us to a group straight
 * away or we get an invite first. Stored as users/{uid}/groupInvites so the adder's phone and the
 * database rules can check it. Missing = [ASK].
 */
enum class GroupInvitePolicy(val wire: String) {
    ASK("ask"),
    DIRECT("direct"),
    ;

    companion object {
        fun fromWire(value: String?): GroupInvitePolicy = entries.firstOrNull { it.wire == value } ?: ASK
    }
}

/**
 * The room that belongs to a chat (a 1:1 chat or a group), like a Discord voice channel: it stays
 * live while anyone is in it, and the others can join whenever they like. [inRoom] = uids of the
 * room's members who are online right now; the link only counts as live while that isn't empty.
 */
data class ChatRoomLink(
    val code: String,
    val startedBy: String,
    val startedAt: Long,
    val inRoom: List<String>,
)
