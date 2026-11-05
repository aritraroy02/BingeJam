package com.syncit.firebase.social

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.model.ChatRoomLink
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Group
import com.syncit.core.model.GroupInvite
import com.syncit.core.model.GroupInvitePolicy
import com.syncit.core.model.GroupMember
import com.syncit.core.social.GroupChats
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.combineAll
import com.syncit.firebase.long
import com.syncit.firebase.snapshots
import com.syncit.firebase.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Group chats (user's request, 2026-11-05):
 * - groups/{gid} {name, createdBy, createdAt, members/{uid} {role: "admin"|"member", joinedAt}}
 * - userGroups/{uid}/{gid}: true, so each of us can list our groups (written with the membership)
 * - groupMessages/{gid}/{pushId} {from, text, sentAt}, groupReads/{me}/{gid} = last opened (server time)
 * - groupRooms/{gid}: the group's room ([ChatRoomLinks])
 * The rules let only members read; only admins add or remove people, and only their own friends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseGroupChats internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val push: PushRelay?,
    private val links: ChatRoomLinks,
) : GroupChats {

    private fun myGroupIds(me: String): Flow<List<String>> =
        db.getReference("userGroups").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { it.key }?.sorted().orEmpty() }
            .distinctUntilChanged()

    override fun observeMyGroups(): Flow<List<Group>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyList())
        myGroupIds(me).flatMapLatest { ids ->
            if (ids.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combineAll(ids.map { id -> db.getReference("groups").child(id).snapshots().map { parse(id, it) } })
                .map { list -> list.filterNotNull().filter { it.isMember(me) }.sortedByDescending { it.createdAt } }
        }
    }

    override fun observeGroup(groupId: String): Flow<Group?> = session.uid.flatMapLatest { me ->
        if (me == null || groupId.isEmpty()) return@flatMapLatest flowOf(null)
        db.getReference("groups").child(groupId).snapshots().map { s -> parse(groupId, s)?.takeIf { it.isMember(me) } }
    }

    override suspend fun create(name: String, memberUids: Collection<String>): String {
        val me = session.requireUid()
        val clean = GroupChats.cleanName(name) ?: throw IllegalArgumentException("Give the group a name")
        val others = memberUids.filter { it != me && it.isNotBlank() }.distinct()
        require(others.isNotEmpty()) { "Pick at least one friend" }
        require(others.size + 1 <= GroupChats.MAX_MEMBERS) { "A group can have up to ${GroupChats.MAX_MEMBERS} people" }
        val id = db.getReference("groups").push().key ?: error("No group id")
        val (direct, ask) = splitByPolicy(others)
        val members = LinkedHashMap<String, Any>()
        members[me] = mapOf("role" to ADMIN, "joinedAt" to ServerValue.TIMESTAMP)
        direct.forEach { members[it] = mapOf("role" to MEMBER, "joinedAt" to ServerValue.TIMESTAMP) }
        val updates = LinkedHashMap<String, Any?>()
        updates["groups/$id"] = mapOf(
            "name" to clean,
            "createdBy" to me,
            "createdAt" to ServerValue.TIMESTAMP,
            "members" to members,
        )
        (listOf(me) + direct).forEach { updates["userGroups/$it/$id"] = true }
        putInvites(updates, me, id, clean, ask)
        db.reference.updateChildren(updates).await()
        ask.forEach { push?.groupInvite(id, it) }
        return id
    }

    override suspend fun rename(groupId: String, name: String) {
        session.requireUid()
        val clean = GroupChats.cleanName(name) ?: throw IllegalArgumentException("Give the group a name")
        db.getReference("groups").child(groupId).child("name").setValue(clean).await()
    }

    override suspend fun addMembers(groupId: String, uids: Collection<String>) {
        val me = session.requireUid()
        val add = uids.filter { it != me && it.isNotBlank() }.distinct()
        if (add.isEmpty()) return
        val name = db.getReference("groups").child(groupId).child("name").get().await().string().orEmpty()
        val (direct, ask) = splitByPolicy(add)
        val updates = LinkedHashMap<String, Any?>()
        direct.forEach { uid ->
            updates["groups/$groupId/members/$uid"] = mapOf("role" to MEMBER, "joinedAt" to ServerValue.TIMESTAMP)
            updates["userGroups/$uid/$groupId"] = true
        }
        putInvites(updates, me, groupId, name, ask)
        db.reference.updateChildren(updates).await()
        ask.forEach { push?.groupInvite(groupId, it) }
    }

    /** Friends who chose "Join directly" are added; everyone else gets an invite (missing = ask). */
    private suspend fun splitByPolicy(uids: List<String>): Pair<List<String>, List<String>> = coroutineScope {
        val policies = uids.map { uid ->
            async { uid to GroupInvitePolicy.fromWire(db.getReference("users").child(uid).child(POLICY).get().await().string()) }
        }.awaitAll()
        policies.filter { it.second == GroupInvitePolicy.DIRECT }.map { it.first } to
            policies.filter { it.second != GroupInvitePolicy.DIRECT }.map { it.first }
    }

    private suspend fun putInvites(updates: MutableMap<String, Any?>, me: String, groupId: String, groupName: String, to: List<String>) {
        if (to.isEmpty()) return
        val myName = db.getReference("users").child(me).child("displayName").get().await().string().orEmpty().take(60)
        to.forEach { uid ->
            updates["groupInvites/$uid/$groupId"] = mapOf(
                "from" to me,
                "fromName" to myName,
                "groupName" to groupName.take(GroupChats.MAX_NAME_LENGTH),
                "sentAt" to ServerValue.TIMESTAMP,
            )
        }
    }

    override fun observeInvites(): Flow<List<GroupInvite>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyList())
        db.getReference("groupInvites").child(me).snapshots().map { s ->
            s?.children?.mapNotNull { c ->
                val id = c.key ?: return@mapNotNull null
                GroupInvite(
                    groupId = id,
                    groupName = c.child("groupName").string().orEmpty().ifBlank { "a group" },
                    fromUid = c.child("from").string().orEmpty(),
                    fromName = c.child("fromName").string().orEmpty(),
                    sentAt = c.child("sentAt").long() ?: 0L,
                )
            }?.sortedByDescending { it.sentAt }.orEmpty()
        }
    }

    override suspend fun acceptInvite(groupId: String) {
        val me = session.requireUid()
        db.reference.updateChildren(
            mapOf(
                "groups/$groupId/members/$me" to mapOf("role" to MEMBER, "joinedAt" to ServerValue.TIMESTAMP),
                "userGroups/$me/$groupId" to true,
                "groupInvites/$me/$groupId" to null,
            ),
        ).await()
    }

    override suspend fun declineInvite(groupId: String) {
        val me = session.requireUid()
        db.getReference("groupInvites").child(me).child(groupId).removeValue().await()
    }

    override fun observeInvitePolicy(): Flow<GroupInvitePolicy> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(GroupInvitePolicy.ASK)
        db.getReference("users").child(me).child(POLICY).snapshots().map { GroupInvitePolicy.fromWire(it?.string()) }
    }

    override suspend fun setInvitePolicy(policy: GroupInvitePolicy) {
        val me = session.requireUid()
        db.getReference("users").child(me).child(POLICY).setValue(policy.wire).await()
    }

    override suspend fun removeMember(groupId: String, uid: String) {
        val me = session.requireUid()
        require(uid != me) { "Use leave to leave the group" }
        db.reference.updateChildren(
            mapOf("groups/$groupId/members/$uid" to null, "userGroups/$uid/$groupId" to null),
        ).await()
    }

    /** If we're the only admin and others stay, the longest-standing member becomes admin first. */
    override suspend fun leave(groupId: String) {
        val me = session.requireUid()
        val group = parse(groupId, db.getReference("groups").child(groupId).get().await())
        val updates = LinkedHashMap<String, Any?>()
        if (group != null && group.isAdmin(me)) {
            val rest = group.members.filter { it.uid != me }
            if (rest.isNotEmpty() && rest.none { it.admin }) {
                val next = rest.minBy { it.joinedAt }
                updates["groups/$groupId/members/${next.uid}/role"] = ADMIN
            }
        }
        links.setInRoomQuietly(db.getReference("groupRooms").child(groupId), me)
        updates["groups/$groupId/members/$me"] = null
        updates["userGroups/$me/$groupId"] = null
        updates["groupReads/$me/$groupId"] = null
        db.reference.updateChildren(updates).await()
    }

    override fun observeMessages(groupId: String, limit: Int): Flow<List<DirectMessage>> =
        session.uid.flatMapLatest { me ->
            if (me == null || groupId.isEmpty()) return@flatMapLatest flowOf(emptyList())
            db.getReference("groupMessages").child(groupId)
                .orderByChild("sentAt").limitToLast(limit.coerceIn(1, MAX_LIMIT))
                .snapshots()
                .map { s -> s?.children?.mapNotNull(::message).orEmpty() }
        }

    override suspend fun send(groupId: String, text: String) {
        val me = session.requireUid()
        val trimmed = text.trim().take(FirebaseDirectMessages.MAX_TEXT_LENGTH)
        if (trimmed.isEmpty()) return
        val ref = db.getReference("groupMessages").child(groupId).push()
        ref.setValue(mapOf("from" to me, "text" to trimmed, "sentAt" to ServerValue.TIMESTAMP)).await()
        ref.key?.let { push?.groupMessage(groupId, it) }
    }

    override fun observeLatest(): Flow<Map<String, DirectMessage>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyMap())
        myGroupIds(me).flatMapLatest { ids ->
            if (ids.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            combineAll(
                ids.map { id ->
                    db.getReference("groupMessages").child(id).orderByChild("sentAt").limitToLast(1).snapshots()
                        .map { s -> id to s?.children?.mapNotNull(::message)?.lastOrNull() }
                },
            ).map { list -> list.mapNotNull { (id, m) -> m?.let { id to it } }.toMap() }
        }
    }

    override fun observeUnreadCounts(): Flow<Map<String, Int>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyMap())
        val reads = db.getReference("groupReads").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { c -> c.key?.let { it to (c.long() ?: 0L) } }?.toMap().orEmpty() }
        combine(myGroupIds(me), reads) { ids, lastRead -> ids.map { it to (lastRead[it] ?: 0L) } }
            .distinctUntilChanged()
            .flatMapLatest { pairs ->
                if (pairs.isEmpty()) return@flatMapLatest flowOf(emptyMap())
                combineAll(
                    pairs.map { (id, since) ->
                        db.getReference("groupMessages").child(id)
                            .orderByChild("sentAt").startAt((since + 1).toDouble()).limitToLast(FirebaseDirectMessages.MAX_UNREAD_COUNTED)
                            .snapshots()
                            .map { s -> id to (s?.children?.count { it.child("from").string() != me } ?: 0) }
                    },
                ).map { list -> list.filter { it.second > 0 }.toMap() }
            }
    }

    override suspend fun markRead(groupId: String) {
        val me = session.requireUid()
        db.getReference("groupReads").child(me).child(groupId).setValue(ServerValue.TIMESTAMP).await()
    }

    override fun observeRoom(groupId: String): Flow<ChatRoomLink?> = session.uid.flatMapLatest { me ->
        if (me == null || groupId.isEmpty()) flowOf(null) else links.observe(db.getReference("groupRooms").child(groupId))
    }

    override suspend fun setRoom(groupId: String, code: String) {
        val me = session.requireUid()
        links.start(db.getReference("groupRooms").child(groupId), me, code)
    }

    override suspend fun setInRoom(groupId: String, inRoom: Boolean) {
        val me = session.requireUid()
        links.setIn(db.getReference("groupRooms").child(groupId), me, inRoom)
    }

    override suspend fun ping(groupId: String, uid: String) {
        val me = session.requireUid()
        if (uid == me) return
        push?.roomPing(groupId, uid)
    }

    private fun message(s: DataSnapshot): DirectMessage? {
        val id = s.key ?: return null
        val from = s.child("from").string() ?: return null
        val text = s.child("text").string() ?: return null
        return DirectMessage(id = id, fromUid = from, text = text, sentAt = s.child("sentAt").long() ?: 0L)
    }

    companion object {
        private const val ADMIN = "admin"
        private const val MEMBER = "member"
        private const val POLICY = "groupInvites"
        private const val MAX_LIMIT = 500

        internal fun parse(id: String, s: DataSnapshot?): Group? {
            if (s == null || !s.exists()) return null
            val name = s.child("name").string()?.takeIf { it.isNotBlank() } ?: return null
            val members = s.child("members").children.mapNotNull { m ->
                val uid = m.key ?: return@mapNotNull null
                GroupMember(uid = uid, admin = m.child("role").string() == ADMIN, joinedAt = m.child("joinedAt").long() ?: 0L)
            }.sortedBy { it.joinedAt }
            return Group(
                id = id,
                name = name,
                createdBy = s.child("createdBy").string().orEmpty(),
                createdAt = s.child("createdAt").long() ?: 0L,
                members = members,
            )
        }
    }
}
