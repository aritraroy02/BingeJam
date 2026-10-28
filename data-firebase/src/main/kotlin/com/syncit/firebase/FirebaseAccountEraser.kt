package com.syncit.firebase

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.syncit.core.social.AvatarException
import com.syncit.firebase.social.FirebaseAvatarStore
import com.syncit.firebase.social.FirebasePresenceRepository
import com.syncit.firebase.social.UsernameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Erases everything the database and the push relay hold for an account, while still signed in
 * (every step needs the account's token). [FirebaseAuthService.deleteAccount] then deletes the
 * Firebase Auth user. Idempotent: a retry after a fresh sign-in finds less (or nothing) and
 * finishes cleanly.
 *
 * 1. Stops this app's own writers for the uid: presence is retired (no more activity/online
 *    writes, onDisconnect cancelled) and the current room is left (member node removed,
 *    onDisconnect cancelled; if we host it, its preview with our display name is removed too).
 * 2. Reads who we're connected to (friends, requests both ways, blocks, DM read markers, join
 *    requests, invites; all owner-readable) and the username key, then sends
 *    [AccountCleanup.deletionPaths] as one atomic multi-path update. A friend accepting our
 *    request between the read and the write could add an entry we didn't see, so we read again
 *    and repeat until nothing is left (at most [MAX_PASSES] updates).
 * 3. DELETE /avatar on the push relay (removes the picture from Workers KV; no-op if none).
 *
 * Not reachable without a full scan (no per-user index): room chat messages and reactions,
 * rooms we hosted or joined other than the current one, previews of other rooms we hosted.
 */
internal class FirebaseAccountEraser(
    private val db: FirebaseDatabase,
    private val presence: FirebasePresenceRepository,
    private val transport: FirebaseSyncTransport,
    private val avatars: FirebaseAvatarStore,
    private val relayConfigured: Boolean = FirebaseConfig.PUSH_RELAY_URL.isNotBlank(),
) {

    private class Connections(val usernameKey: String?, val related: Set<String>) {
        val isEmpty: Boolean get() = usernameKey == null && related.isEmpty()
    }

    /** The deletion didn't finish (erase or the Auth delete failed): let presence run again. */
    fun abandon(uid: String) = presence.unretire(uid)

    suspend fun erase(uid: String) {
        presence.retire(uid)
        leaveActiveRoom(uid)

        val done = withTimeoutOrNull(DATABASE_TIMEOUT_MS) {
            var pass = 0
            while (true) {
                val found = readConnections(uid)
                if (pass > 0 && found.isEmpty) break
                db.reference.updateChildren(AccountCleanup.deletionPaths(uid, found.usernameKey, found.related)).await()
                if (++pass == MAX_PASSES) break
            }
        }
        // Writes still queued after a timeout are sent when the connection is back; deleting
        // again on retry is harmless.
        if (done == null) throw IOException("Couldn't reach the server. Check your connection and try again.")

        if (relayConfigured) {
            try {
                avatars.delete()
            } catch (e: AvatarException) {
                throw IOException("Couldn't delete your profile picture. Try again.", e)
            }
        }
    }

    private suspend fun readConnections(uid: String): Connections = coroutineScope {
        val lists = AccountCleanup.RELATED_SOURCES.map { root ->
            async { db.getReference(root).child(uid).get().await().children.mapNotNull { it.key } }
        }
        val username = db.getReference("users").child(uid).child("username").get().await().string()
            ?.takeIf { UsernameRules.isValid(it) }
        // Only release the key if it really is ours; the rules deny deleting anyone else's, and
        // one denied path would fail the whole update.
        val key = username?.let { UsernameRules.key(it) }
            ?.takeIf { db.getReference("usernames").child(it).get().await().string() == uid }
        Connections(key, lists.awaitAll().flatten().toSet())
    }

    /** Best effort: the room may be gone already, and nothing here blocks the deletion. */
    private suspend fun leaveActiveRoom(uid: String) {
        val code = transport.activeRoomCode ?: return
        try {
            val hostUid = db.getReference("rooms").child(code).child("hostUid").get().await().string()
            if (hostUid == uid) db.getReference("previews").child(code).removeValue().await()
            transport.leaveRoom(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't leave room $code during account deletion", e)
        }
    }

    private companion object {
        const val TAG = "SyncItAuth"
        const val MAX_PASSES = 3
        const val DATABASE_TIMEOUT_MS = 30_000L
    }
}
