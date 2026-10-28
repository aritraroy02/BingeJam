package com.syncit.core.social

import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.RoomInvite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Online status and "now playing", visible to friends only. Implemented in :data-firebase.
 */
interface PresenceRepository {
    /** Keeps presence/{uid}/online true while connected; the server flips it off on disconnect. */
    fun start()

    fun stop()

    /** Publishes (or clears, with null) what we're playing. Server-stamps startedAt on change. */
    suspend fun publishActivity(update: ActivityUpdate?)
}

/**
 * What the tracker publishes; the repository adds the server timestamp. [inWatchParty]: we're in a
 * synced room for this title (the code itself is never published). [watchingWith]: who else is in
 * it, already worded ("Somesh and Ayan"); only set while [inWatchParty].
 */
data class ActivityUpdate(
    val app: ActivityApp,
    val metadata: NowPlayingMetadata?,
    val playing: Boolean,
    val inWatchParty: Boolean,
    val watchingWith: String? = null,
)

/**
 * Watches the user's media sessions (MediaSession only) and publishes what they're playing
 * for the apps they chose to share. Implemented in :media; started by :app once signed in.
 */
interface ActivityTracker {
    /**
     * Apps the user chose to share (ActivitySetup page / Settings). Empty = share nothing.
     * [inWatchParty]: true while the user is syncing a room's title (status SYNCING/CORRECTING/
     * CATCHING_UP). [watchingWith]: the other room members, worded for friends ("Somesh and
     * Ayan"), or null. Start only after the signed-in account has completed the activity setup page.
     */
    fun start(sharedApps: StateFlow<Set<ActivityApp>>, inWatchParty: StateFlow<Boolean>, watchingWith: StateFlow<String?>)

    fun stop()

    /** What would be shared right now (for a "you're sharing…" preview in Settings). */
    val current: StateFlow<Activity?>
}

/**
 * Asking to join a friend's watch party, and inviting friends to yours. Movies/TV only.
 * Implemented in :data-firebase.
 */
interface WatchInvites {
    /** Ask [hostUid] to let us join what they're watching (from their shared activity). */
    suspend fun requestToJoin(hostUid: String, app: ActivityApp, title: String?)

    /** Requests friends sent us (we're the host). */
    fun observeIncomingJoinRequests(): Flow<List<JoinRequest>>

    /** Our own outgoing requests, to react when the host accepts (status + roomCode). */
    fun observeMyJoinRequests(): Flow<List<JoinRequest>>

    /** Host accepts: the requester gets [roomCode] and joins it. */
    suspend fun acceptJoin(fromUid: String, roomCode: String)

    suspend fun declineJoin(fromUid: String)

    suspend fun clearMyJoinRequest(hostUid: String)

    suspend fun inviteToRoom(friendUid: String, roomCode: String, app: ActivityApp?, title: String?)

    fun observeInvites(): Flow<List<RoomInvite>>

    suspend fun dismissInvite(fromUid: String)
}
