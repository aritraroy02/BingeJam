package com.syncit.firebase.social

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.syncit.core.model.ChatRoomLink
import com.syncit.firebase.await
import com.syncit.firebase.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The room that belongs to a chat: groupRooms/{groupId} or dmRooms/{dmId} =
 * {code, startedBy, startedAt, in/{uid}: true}. The room itself is the usual rooms/{code}; only its
 * members can read that, so the link keeps its own "who's in" list for the rest of the chat. Each
 * phone adds itself when it starts or joins the room and removes itself when it leaves; onDisconnect
 * removes it if the app dies or loses the connection, and it's written again on reconnect while
 * we're still in the room. The link counts as live while that list isn't empty.
 */
internal class ChatRoomLinks(db: FirebaseDatabase) {

    /** Our own in/{uid} entries we're keeping alive (path → ref). */
    private val marked = HashMap<String, DatabaseReference>()

    init {
        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) != true) return
                val refs = synchronized(marked) { marked.values.toList() }
                refs.forEach(::arm)
            }

            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    fun observe(link: DatabaseReference): Flow<ChatRoomLink?> =
        link.snapshots().map(::parse).distinctUntilChanged()

    /** Starts a new room for the chat: replaces any old link, with only us in it so far. */
    suspend fun start(link: DatabaseReference, me: String, code: String) {
        require(ROOM_CODE.matches(code))
        link.setValue(
            mapOf(
                "code" to code,
                "startedBy" to me,
                "startedAt" to ServerValue.TIMESTAMP,
                "in" to mapOf(me to true),
            ),
        ).await()
        setIn(link, me, true)
    }

    suspend fun setIn(link: DatabaseReference, me: String, inRoom: Boolean) {
        val ref = link.child("in").child(me)
        val key = ref.toString()
        if (inRoom) {
            synchronized(marked) { marked[key] = ref }
            arm(ref)
        } else {
            synchronized(marked) { marked.remove(key) }
            ref.onDisconnect().cancel()
            ref.removeValue().await()
        }
    }

    /** Like setIn(false) but never waits or throws (leaving a group, where the write may be refused). */
    fun setInRoomQuietly(link: DatabaseReference, me: String) {
        val ref = link.child("in").child(me)
        synchronized(marked) { marked.remove(ref.toString()) }
        ref.onDisconnect().cancel()
        ref.removeValue().addOnFailureListener { e -> Log.d(TAG, "Room presence not removed", e) }
    }

    /** onDisconnect first, so the entry can't outlive the connection; both go out in this order. */
    private fun arm(ref: DatabaseReference) {
        ref.onDisconnect().removeValue().addOnFailureListener { e -> Log.d(TAG, "Room onDisconnect not set", e) }
        ref.setValue(true).addOnFailureListener { e -> Log.d(TAG, "Room presence not written", e) }
    }

    companion object {
        private const val TAG = "SyncItChatRooms"
        val ROOM_CODE = Regex("^[A-HJ-NP-Z2-9]{6}$")

        fun parse(s: DataSnapshot?): ChatRoomLink? {
            if (s == null) return null
            val code = s.child("code").value as? String ?: return null
            if (!ROOM_CODE.matches(code)) return null
            val inRoom = s.child("in").children.filter { it.value == true }.mapNotNull { it.key }.sorted()
            if (inRoom.isEmpty()) return null
            return ChatRoomLink(
                code = code,
                startedBy = s.child("startedBy").value as? String ?: "",
                startedAt = (s.child("startedAt").value as? Number)?.toLong() ?: 0L,
                inRoom = inRoom,
            )
        }
    }
}
