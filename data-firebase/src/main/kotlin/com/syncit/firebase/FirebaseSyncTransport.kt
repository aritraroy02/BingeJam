package com.syncit.firebase

import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.syncit.core.config.RoomLinks
import com.syncit.core.model.ChatMessage
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.Reaction
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.StreamingService
import com.syncit.core.sync.ServerClock
import com.syncit.core.transport.RoomNotFoundException
import com.syncit.core.transport.SyncTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.security.SecureRandom

/**
 * [SyncTransport] on Firebase Realtime Database + Anonymous Auth.
 * Data model and rules: PLAN.md and data-firebase/firebase/database.rules.json.
 */
class FirebaseSyncTransport(
    private val auth: FirebaseAuth,
    private val db: FirebaseDatabase,
) : SyncTransport {

    private val rooms: DatabaseReference = db.getReference("rooms")
    private val chats: DatabaseReference = db.getReference("chats")
    private val reactions: DatabaseReference = db.getReference("reactions")
    private val previews: DatabaseReference = db.getReference("previews")

    override val serverClock: ServerClock = FirebaseServerClock(db)

    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    override val currentUid: StateFlow<String?> = _uid.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val random = SecureRandom()
    private var presence: Presence? = null

    init {
        auth.addAuthStateListener { _uid.value = it.currentUser?.uid }
        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _connected.value = snapshot.getValue(Boolean::class.java) == true
            }

            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    // ---- Auth ----

    override suspend fun ensureSignedIn(): String {
        auth.currentUser?.let { return it.uid }
        val result = auth.signInAnonymously().await()
        val uid = result.user?.uid ?: error("Anonymous sign-in returned no user")
        _uid.value = uid
        return uid
    }

    // ---- Rooms ----

    override suspend fun createRoom(displayName: String): String {
        val uid = ensureSignedIn()
        repeat(MAX_CODE_ATTEMPTS) {
            val code = newRoomCode()
            val room = mapOf(
                "hostUid" to uid,
                "createdAt" to ServerValue.TIMESTAMP,
                "members" to mapOf(uid to memberFields(displayName)),
            )
            try {
                // Rules only allow creating a room that doesn't exist yet, so a taken code
                // comes back as "permission denied" and we try another.
                rooms.child(code).setValue(room).await()
                startPresence(code, uid)
                writeInitialPreview(code, displayName)
                return code
            } catch (e: Exception) {
                if (!e.isPermissionDenied()) throw e
            }
        }
        // 32^6 codes, so five collisions in a row almost never happen: repeated denials usually
        // mean the rules aren't published or anonymous auth is off.
        error("Couldn't create a room (permission denied). Check the database rules and anonymous sign-in.")
    }

    override suspend fun joinRoom(code: String, displayName: String) {
        val uid = ensureSignedIn()
        try {
            // Joining = writing our own member node. Rules reject it if the room doesn't exist.
            rooms.child(code).child("members").child(uid).updateChildren(memberFields(displayName)).await()
        } catch (e: Exception) {
            if (e.isPermissionDenied()) throw RoomNotFoundException(code)
            throw e
        }
        startPresence(code, uid)
    }

    override suspend fun leaveRoom(code: String) {
        val uid = _uid.value ?: return
        stopPresence()
        val memberRef = rooms.child(code).child("members").child(uid)
        memberRef.onDisconnect().cancel().await()
        memberRef.removeValue().await()
    }

    override fun observeRoom(code: String): Flow<Room?> = callbackFlow {
        val ref = rooms.child(code)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(RoomParser.room(code, snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                // Typically "permission denied": we're no longer a member.
                trySend(null)
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ---- Playback state ----

    override suspend fun writeState(code: String, broadcast: StateBroadcast) {
        val uid = ensureSignedIn()
        // One atomic multi-field update. The server stamps updatedAt and increments seq while
        // applying it, so concurrent writers are ordered by arrival at the server: last write
        // wins, and seq/updatedAt agree on which one was last.
        val update = mapOf(
            "status" to broadcast.status.wire,
            "positionMs" to broadcast.positionMs.coerceAtLeast(0L),
            "updatedAt" to ServerValue.TIMESTAMP,
            "updatedBy" to uid,
            "seq" to ServerValue.increment(1),
        )
        rooms.child(code).child("state").updateChildren(update).await()
    }

    override suspend fun reportPosition(code: String, positionMs: Long) {
        val uid = ensureSignedIn()
        rooms.child(code).child("members").child(uid).updateChildren(
            mapOf(
                "positionMs" to positionMs.coerceAtLeast(0L),
                "reportedAt" to ServerValue.TIMESTAMP,
            ),
        ).await()
    }

    // ---- Preview / now playing ----

    /** Rules need the room to exist first (only its host may write the preview). */
    private suspend fun writeInitialPreview(code: String, displayName: String) {
        try {
            previews.child(code).setValue(
                mapOf("hostName" to cleanName(displayName), "updatedAt" to ServerValue.TIMESTAMP),
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The room works without a preview; invitees just see the bare code.
            Log.w(TAG, "Couldn't write the room preview", e)
        }
    }

    override fun observePreview(code: String): Flow<RoomPreview?> = callbackFlow {
        ensureSignedIn()
        val ref = previews.child(code)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(RoomParser.preview(code, snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(null)
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    override suspend fun setNowPlaying(code: String, service: StreamingService, metadata: NowPlayingMetadata) {
        ensureSignedIn()
        // updateChildren with null deletes a field, so an app that drops its artwork clears ours too.
        previews.child(code).updateChildren(
            mapOf(
                "service" to service.name,
                "title" to metadata.title?.take(MAX_META_LENGTH),
                "subtitle" to metadata.subtitle?.take(MAX_META_LENGTH),
                "durationMs" to metadata.durationMs,
                "artwork" to metadata.artworkJpeg
                    ?.takeIf { it.size <= MAX_ARTWORK_BYTES }
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                "updatedAt" to ServerValue.TIMESTAMP,
            ),
        ).await()
    }

    // ---- Title & countdown ----

    override suspend fun setTitle(code: String, service: StreamingService, deepLink: String) {
        val uid = ensureSignedIn()
        rooms.child(code).child("title").setValue(
            mapOf(
                "service" to service.name,
                "deepLink" to deepLink,
                "setBy" to uid,
                "setAt" to ServerValue.TIMESTAMP,
            ),
        ).await()
    }

    override suspend fun startCountdown(code: String, leadMs: Long) {
        val uid = ensureSignedIn()
        // requestedAt is server time; every phone counts down to requestedAt + leadMs on its
        // own estimate of server time, so they all hit "play" together.
        rooms.child(code).child("countdown").setValue(
            mapOf(
                "requestedAt" to ServerValue.TIMESTAMP,
                "leadMs" to leadMs,
                "startedBy" to uid,
            ),
        ).await()
    }

    // ---- Chat & reactions ----

    override fun observeChat(code: String, limit: Int): Flow<List<ChatMessage>> = callbackFlow {
        val query = chats.child(code).orderByChild("sentAt").limitToLast(limit)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.children.mapNotNull(RoomParser::chat))
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        query.addValueEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    override suspend fun sendChat(code: String, displayName: String, text: String) {
        val uid = ensureSignedIn()
        val trimmed = text.trim().take(MAX_CHAT_LENGTH)
        if (trimmed.isEmpty()) return
        chats.child(code).push().setValue(
            mapOf("uid" to uid, "name" to displayName, "text" to trimmed, "sentAt" to ServerValue.TIMESTAMP),
        ).await()
    }

    override fun observeReactions(code: String, sinceServerMs: Long): Flow<Reaction> = callbackFlow {
        val query = reactions.child(code).orderByChild("sentAt").startAt(sinceServerMs.toDouble())
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                RoomParser.reaction(snapshot)?.let { trySend(it) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        query.addChildEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    override suspend fun sendReaction(code: String, displayName: String, emoji: String) {
        val uid = ensureSignedIn()
        reactions.child(code).push().setValue(
            mapOf("uid" to uid, "name" to displayName, "emoji" to emoji, "sentAt" to ServerValue.TIMESTAMP),
        ).await()
    }

    // ---- Presence ----

    private class Presence(
        val connectedRef: DatabaseReference,
        val listener: ValueEventListener,
    )

    /**
     * Keeps members/{uid}/online accurate. Every time the connection (re)opens we first
     * register a server-side onDisconnect that flips us offline, then mark ourselves online.
     * The server runs onDisconnect itself if the app dies or loses network, so other members
     * see us go offline even if we never get to say goodbye.
     */
    private fun startPresence(code: String, uid: String) {
        stopPresence()
        val memberRef = rooms.child(code).child("members").child(uid)
        val connectedRef = db.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) != true) return
                memberRef.onDisconnect()
                    .updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
                    .addOnSuccessListener {
                        memberRef.updateChildren(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
                    }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        connectedRef.addValueEventListener(listener)
        presence = Presence(connectedRef, listener)
    }

    private fun stopPresence() {
        presence?.let { it.connectedRef.removeEventListener(it.listener) }
        presence = null
    }

    // ---- Helpers ----

    private fun cleanName(displayName: String): String = displayName.trim().take(MAX_NAME_LENGTH).ifEmpty { "Guest" }

    private fun memberFields(displayName: String): Map<String, Any> = mapOf(
        "name" to cleanName(displayName),
        "online" to true,
        "joinedAt" to ServerValue.TIMESTAMP,
        "lastSeen" to ServerValue.TIMESTAMP,
    )

    private fun newRoomCode(): String = buildString {
        repeat(RoomLinks.CODE_LENGTH) {
            append(RoomLinks.CODE_ALPHABET[random.nextInt(RoomLinks.CODE_ALPHABET.length)])
        }
    }

    companion object {
        private const val MAX_CODE_ATTEMPTS = 5
        private const val MAX_NAME_LENGTH = 40
        private const val MAX_CHAT_LENGTH = 500
        private const val MAX_META_LENGTH = 200

        /** Base64 grows 4/3, so this stays under the rules' 70,000-character limit. */
        private const val MAX_ARTWORK_BYTES = 52_000
        private const val TAG = "SyncItFirebase"

        fun create(): FirebaseSyncTransport {
            val db = if (FirebaseConfig.DATABASE_URL.isBlank()) {
                FirebaseDatabase.getInstance()
            } else {
                FirebaseDatabase.getInstance(FirebaseConfig.DATABASE_URL)
            }
            return FirebaseSyncTransport(FirebaseAuth.getInstance(), db)
        }
    }
}
