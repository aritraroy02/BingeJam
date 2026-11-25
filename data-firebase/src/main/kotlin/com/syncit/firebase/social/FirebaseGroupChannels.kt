package com.syncit.firebase.social

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.model.DirectMessage
import com.syncit.core.social.ChannelKind
import com.syncit.core.social.GroupChannel
import com.syncit.core.social.GroupChannels
import com.syncit.core.social.GroupChannels.Companion.GENERAL
import com.syncit.core.social.GroupChannels.Companion.LOUNGE
import com.syncit.core.social.VoiceOccupant
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.bool
import com.syncit.firebase.long
import com.syncit.firebase.snapshots
import com.syncit.firebase.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** [GroupChannels] on the Realtime Database; the same paths and rules as the PC app (desktop/src/renderer/src/data/channels.ts). */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseGroupChannels internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : GroupChannels {

    private fun <T> signedIn(empty: T, body: (String) -> Flow<T>): Flow<T> =
        session.uid.flatMapLatest { me -> if (me == null) flowOf(empty) else body(me) }

    override fun observeChannels(groupId: String): Flow<List<GroupChannel>> = signedIn(emptyList()) {
        db.getReference("groups").child(groupId).child("channels").snapshots().map { s -> s?.children?.mapNotNull(::channel).orEmpty() }
    }

    override fun observeVoice(groupId: String): Flow<Map<String, List<VoiceOccupant>>> = signedIn(emptyMap()) {
        db.getReference("groupVoice").child(groupId).snapshots().map { s -> parseVoice(s) }
    }

    override fun observeVoiceStatuses(groupId: String): Flow<Map<String, String>> = signedIn(emptyMap()) {
        db.getReference("groupVoiceStatus").child(groupId).snapshots().map { s ->
            s?.children?.mapNotNull { c ->
                val text = c.child("text").string()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                (c.key ?: return@mapNotNull null) to text.take(GroupChannels.STATUS_MAX)
            }?.toMap().orEmpty()
        }
    }

    override fun observeReads(groupId: String): Flow<Map<String, Long>> = signedIn(emptyMap()) { me ->
        db.getReference("groupChannelReads").child(me).child(groupId).snapshots().map { s ->
            s?.children?.mapNotNull { c -> c.key?.let { it to (c.long() ?: 0L) } }?.toMap().orEmpty()
        }
    }

    override fun observeNewest(groupId: String, channelId: String): Flow<DirectMessage?> = signedIn(null) {
        db.getReference("groupChannelMessages").child(groupId).child(channelId)
            .orderByChild("sentAt").limitToLast(1).snapshots()
            .map { s -> s?.children?.mapNotNull(::message)?.lastOrNull() }
    }

    override fun observeMessages(groupId: String, channelId: String, limit: Int): Flow<List<DirectMessage>> = signedIn(emptyList()) {
        db.getReference("groupChannelMessages").child(groupId).child(channelId)
            .orderByChild("sentAt").limitToLast(limit.coerceIn(1, 500)).snapshots()
            .map { s -> s?.children?.mapNotNull(::message).orEmpty() }
    }

    override suspend fun send(groupId: String, channelId: String, text: String) {
        val me = session.requireUid()
        val trimmed = text.trim().take(FirebaseDirectMessages.MAX_TEXT_LENGTH)
        if (trimmed.isEmpty() || channelId == GENERAL) return
        db.getReference("groupChannelMessages").child(groupId).child(channelId).push()
            .setValue(mapOf("from" to me, "text" to trimmed, "sentAt" to ServerValue.TIMESTAMP)).await()
    }

    override suspend fun markRead(groupId: String, channelId: String) {
        val me = session.requireUid()
        db.getReference("groupChannelReads").child(me).child(groupId).child(channelId).setValue(ServerValue.TIMESTAMP).await()
    }

    override suspend fun create(groupId: String, name: String, kind: ChannelKind): String {
        val me = session.requireUid()
        val clean = cleanName(name, kind).ifEmpty { throw IllegalArgumentException("Give the channel a name") }
        val channels = db.getReference("groups").child(groupId).child("channels")
        val stored = channels.get().await().children.mapNotNull(::channel)
        val ref = channels.push()
        val id = ref.key ?: error("No channel id")
        ref.setValue(
            mapOf("name" to clean, "kind" to kind.wire, "order" to nextOrder(stored, kind), "createdBy" to me, "createdAt" to ServerValue.TIMESTAMP),
        ).await()
        return id
    }

    override suspend fun rename(groupId: String, channelId: String, name: String, kind: ChannelKind) {
        session.requireUid()
        val clean = cleanName(name, kind).ifEmpty { throw IllegalArgumentException("Give the channel a name") }
        db.getReference("groups").child(groupId).child("channels").child(channelId).child("name").setValue(clean).await()
    }

    override suspend fun delete(groupId: String, channelId: String) {
        session.requireUid()
        require(channelId != GENERAL && channelId != LOUNGE) { "This channel can't be deleted" }
        val inVoice = db.getReference("groupVoice").child(groupId).child(channelId).get().await().children.mapNotNull { it.key }
        val changes = HashMap<String, Any?>()
        changes["groups/$groupId/channels/$channelId"] = null
        changes["groupChannelMessages/$groupId/$channelId"] = null
        changes["groupVoiceStatus/$groupId/$channelId"] = null
        inVoice.forEach { changes["groupVoice/$groupId/$channelId/$it"] = null }
        db.reference.updateChildren(changes).await()
    }

    override suspend fun joinVoice(groupId: String, channelId: String, name: String, muted: Boolean, deafened: Boolean) {
        val me = session.requireUid()
        val entry = db.getReference("groupVoice").child(groupId).child(channelId).child(me)
        entry.onDisconnect().removeValue().await()
        entry.setValue(
            mapOf("name" to name.take(40).ifBlank { "Friend" }, "since" to ServerValue.TIMESTAMP, "muted" to muted, "deafened" to deafened),
        ).await()
    }

    override suspend fun updateVoice(groupId: String, channelId: String, muted: Boolean, deafened: Boolean) {
        val me = session.requireUid()
        db.getReference("groupVoice").child(groupId).child(channelId).child(me)
            .updateChildren(mapOf("muted" to muted, "deafened" to deafened)).await()
    }

    override suspend fun leaveVoice(groupId: String, channelId: String) {
        val me = session.requireUid()
        val channel = db.getReference("groupVoice").child(groupId).child(channelId)
        val others = runCatching { channel.get().await().children.mapNotNull { it.key }.filter { it != me } }.getOrDefault(listOf(me))
        if (others.isEmpty()) runCatching { db.getReference("groupVoiceStatus").child(groupId).child(channelId).removeValue().await() }
        val entry = channel.child(me)
        entry.removeValue().await()
        runCatching { entry.onDisconnect().cancel().await() }
    }

    override suspend fun setStatus(groupId: String, channelId: String, text: String) {
        val me = session.requireUid()
        val clean = text.replace(Regex("\\s+"), " ").trim().take(GroupChannels.STATUS_MAX)
        val target = db.getReference("groupVoiceStatus").child(groupId).child(channelId)
        if (clean.isEmpty()) target.removeValue().await()
        else target.setValue(mapOf("text" to clean, "by" to me, "at" to ServerValue.TIMESTAMP)).await()
    }

    private fun message(s: DataSnapshot): DirectMessage? {
        val id = s.key ?: return null
        val from = s.child("from").string() ?: return null
        val text = s.child("text").string() ?: return null
        return DirectMessage(id = id, fromUid = from, text = text, sentAt = s.child("sentAt").long() ?: 0L)
    }

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,40}$")

        internal fun channel(s: DataSnapshot): GroupChannel? {
            val id = s.key?.takeIf { ID.matches(it) && it != GENERAL && it != LOUNGE } ?: return null
            val kind = ChannelKind.fromWire(s.child("kind").string()) ?: return null
            val name = s.child("name").string()?.takeIf { it.isNotBlank() }?.take(GroupChannels.NAME_MAX) ?: return null
            return GroupChannel(
                id = id,
                name = name,
                kind = kind,
                order = (s.child("order").value as? Number)?.toDouble() ?: 0.0,
                createdAt = s.child("createdAt").long() ?: 0L,
            )
        }

        internal fun parseVoice(s: DataSnapshot?): Map<String, List<VoiceOccupant>> {
            if (s == null) return emptyMap()
            val out = LinkedHashMap<String, List<VoiceOccupant>>()
            for (c in s.children) {
                val cid = c.key ?: continue
                val people = c.children.mapNotNull { p ->
                    val since = p.child("since").long() ?: return@mapNotNull null
                    VoiceOccupant(
                        uid = p.key ?: return@mapNotNull null,
                        name = p.child("name").string()?.takeIf { it.isNotBlank() } ?: "Friend",
                        since = since,
                        muted = p.child("muted").bool() == true,
                        deafened = p.child("deafened").bool() == true,
                        live = p.child("live").bool() == true,
                    )
                }.sortedWith(compareBy({ it.since }, { it.uid }))
                if (people.isNotEmpty()) out[cid] = people
            }
            return out
        }

        /** Text channels read like Discord's ("movie-picks"); voice channels keep what was typed. Same as the PC. */
        fun cleanName(name: String, kind: ChannelKind): String {
            val collapsed = name.replace(Regex("\\s+"), " ").trim()
            if (kind == ChannelKind.VOICE) return collapsed.take(GroupChannels.NAME_MAX).trim()
            return collapsed.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}_ -]"), "")
                .trim()
                .replace(Regex("[ -]+"), "-")
                .take(GroupChannels.NAME_MAX)
                .trim('-')
        }

        /** After every channel of its kind. */
        fun nextOrder(stored: List<GroupChannel>, kind: ChannelKind): Double =
            minOf(1_000_000.0, (stored.filter { it.kind == kind }.maxOfOrNull { it.order } ?: -1.0).coerceAtLeast(-1.0) + 1)
    }
}
