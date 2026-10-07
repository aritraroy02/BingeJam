package com.syncit.core.transport

import com.syncit.core.model.ChatMessage
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.Reaction
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.StreamingService
import com.syncit.core.sync.ServerClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Room backend. v1 is Firebase Realtime Database (:data-firebase); this interface is what a
 * future Node.js + Socket.IO implementation would provide too.
 *
 * Every timestamp written through this interface is stamped by the server.
 */
interface SyncTransport {
    val currentUid: StateFlow<String?>
    val connected: StateFlow<Boolean>
    val serverClock: ServerClock

    /** Signs in anonymously if needed and returns our uid. */
    suspend fun ensureSignedIn(): String

    /**
     * Creates a room with us as host and member, plus its preview (host name); returns the
     * 6-character code.
     */
    suspend fun createRoom(displayName: String): String

    /** Adds us as a member and starts presence. Throws [RoomNotFoundException]. */
    suspend fun joinRoom(code: String, displayName: String)

    suspend fun leaveRoom(code: String)

    /** Emits null if the room doesn't exist or we lost access to it. */
    fun observeRoom(code: String): Flow<Room?>

    /** Writes rooms/{code}/state; updatedAt and seq are set server-side. */
    suspend fun writeState(code: String, broadcast: StateBroadcast)

    /** Writes our position to members/{uid} for the drift overlay. */
    suspend fun reportPosition(code: String, positionMs: Long)

    suspend fun setTitle(code: String, service: StreamingService, deepLink: String)

    /**
     * The room's preview: host name and what the host is playing. Readable by any signed-in
     * user who knows the code (no membership needed), so invitees can see it before joining.
     * Emits null if there's no preview for [code].
     */
    fun observePreview(code: String): Flow<RoomPreview?>

    /** Host only: publishes what the host's streaming app says is playing. */
    suspend fun setNowPlaying(code: String, service: StreamingService, metadata: NowPlayingMetadata)

    suspend fun startCountdown(code: String, leadMs: Long)

    fun observeChat(code: String, limit: Int = 100): Flow<List<ChatMessage>>

    suspend fun sendChat(code: String, displayName: String, text: String)

    /** Reactions sent at or after [sinceServerMs]. */
    fun observeReactions(code: String, sinceServerMs: Long): Flow<Reaction>

    suspend fun sendReaction(code: String, displayName: String, emoji: String)
}

class RoomNotFoundException(code: String) : Exception("No room with code $code")
