package com.syncit.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.syncit.core.config.RoomLinks
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Thrown when something needs an account and nobody is signed in (v2 has no anonymous users). */
class NotSignedInException : Exception("Sign in first")

/** Thrown when chat, presence or watch invites are attempted with someone who isn't a friend. */
class NotFriendsException(message: String = "You can only do that with friends") : Exception(message)

/**
 * The signed-in account, shared by the transport and all repositories.
 *
 * v1 users were anonymous. v2 requires Google or e-mail-link accounts, so a leftover anonymous
 * user from an older install counts as signed out everywhere ([uid] is null); signing in with
 * Google or an e-mail link replaces it.
 */
internal class FirebaseSession(val auth: FirebaseAuth) {
    private val _uid = MutableStateFlow(auth.currentUser.accountUid())
    val uid: StateFlow<String?> = _uid.asStateFlow()

    init {
        auth.addAuthStateListener { _uid.value = it.currentUser.accountUid() }
    }

    /** Current account uid, read straight from FirebaseAuth so it's never stale. */
    val currentUid: String? get() = auth.currentUser.accountUid()

    fun requireUid(): String = currentUid ?: throw NotSignedInException()

    companion object {
        fun FirebaseUser?.accountUid(): String? = this?.takeUnless { it.isAnonymous }?.uid
    }
}

/**
 * Live value of [this] query. A cancelled listener (usually "permission denied", e.g. after an
 * unfriend) emits null and completes instead of failing, so one revoked path doesn't tear down
 * a combined flow.
 */
internal fun Query.snapshots(): Flow<DataSnapshot?> = callbackFlow {
    val listener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            trySend(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            Log.w(TAG, "Listener cancelled at ${this@snapshots}: ${error.message}")
            trySend(null)
            close()
        }
    }
    addValueEventListener(listener)
    awaitClose { removeEventListener(listener) }
}

/** Live value of [this] query; a cancelled listener fails the flow with the DatabaseException. */
internal fun Query.snapshotsOrError(): Flow<DataSnapshot> = callbackFlow {
    val listener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            trySend(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            close(error.toException())
        }
    }
    addValueEventListener(listener)
    awaitClose { removeEventListener(listener) }
}

/** combine() that emits an empty list for no flows (plain combine would never emit). */
internal inline fun <reified T> combineAll(flows: List<Flow<T>>): Flow<List<T>> =
    if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }

/** Same format the rules enforce on rooms/$code. */
internal fun isRoomCode(code: String): Boolean =
    code.length == RoomLinks.CODE_LENGTH && code.all { it in RoomLinks.CODE_ALPHABET }

internal fun DataSnapshot.string(): String? = value as? String
internal fun DataSnapshot.long(): Long? = (value as? Number)?.toLong()
internal fun DataSnapshot.bool(): Boolean? = value as? Boolean

private const val TAG = "SyncItFirebase"
