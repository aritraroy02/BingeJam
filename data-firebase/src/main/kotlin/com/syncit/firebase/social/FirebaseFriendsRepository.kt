package com.syncit.firebase.social

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.model.Friend
import com.syncit.core.model.FriendRequest
import com.syncit.core.social.FriendRequestException
import com.syncit.core.social.FriendsRepository
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.combineAll
import com.syncit.firebase.isPermissionDenied
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
 * Friend requests and mutual friendships.
 *
 *   friendRequests/{to}/{from}       the request (recipient lists theirs here)
 *   sentFriendRequests/{from}/{to}   true; index so the sender can list outgoing requests
 *                                    (RTDB can't query friendRequests/ by child key across parents)
 *   friends/{a}/{b} + friends/{b}/{a} {since}, written together on accept
 *   blocked/{me}/{them}              true
 *
 * Every state change is one multi-path update, so the two sides never disagree. The rules only
 * allow writing friends/… when the matching request exists (see database.rules.json).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseFriendsRepository internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val push: PushRelay? = null,
) : FriendsRepository {

    private val root: DatabaseReference = db.reference

    override fun observeFriends(): Flow<List<Friend>> = forUid { me ->
        db.getReference("friends").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { c -> c.key?.let { it to (c.child("since").long() ?: 0L) } }.orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { pairs ->
                combineAll(
                    pairs.map { (uid, since) ->
                        combine(
                            db.getReference("users").child(uid).snapshots(),
                            db.getReference("presence").child(uid).snapshots(),
                        ) { profile, presence ->
                            SocialParser.profile(uid, profile)?.let { Friend(it, since, SocialParser.presence(presence)) }
                        }
                    },
                ).map { list -> list.filterNotNull().sortedBy { it.profile.displayName.lowercase() } }
            }
    }

    override fun observeIncomingRequests(): Flow<List<FriendRequest>> = forUid { me ->
        db.getReference("friendRequests").child(me).snapshots().map { s ->
            s?.children?.mapNotNull { SocialParser.friendRequest(me, it) }.orEmpty().sortedByDescending { it.sentAt }
        }
    }

    override fun observeOutgoingRequests(): Flow<List<FriendRequest>> = forUid { me ->
        db.getReference("sentFriendRequests").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { it.key }.orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { targets ->
                combineAll(
                    targets.map { to ->
                        db.getReference("friendRequests").child(to).child(me).snapshots()
                            .map { SocialParser.friendRequest(to, it) }
                    },
                ).map { list -> list.filterNotNull().sortedByDescending { it.sentAt } }
            }
    }

    override suspend fun sendRequest(toUsername: String, via: String?) {
        val me = session.requireUid()
        val name = UsernameRules.normalize(toUsername)
        if (!UsernameRules.isValid(name)) throw FriendRequestException("No one is called @$name")
        val myProfile = SocialParser.profile(me, db.getReference("users").child(me).get().await())
            ?: throw FriendRequestException("Pick a username first")
        val them = db.getReference("usernames").child(UsernameRules.key(name)).get().await().string()
            ?: throw FriendRequestException("No one is called @$name")
        if (them == me) throw FriendRequestException("That's you!")
        if (db.getReference("friends").child(me).child(them).get().await().exists()) {
            throw FriendRequestException("You're already friends with @$name")
        }
        if (db.getReference("blocked").child(me).child(them).get().await().exists()) {
            throw FriendRequestException("You blocked @$name. Unblock them first.")
        }
        // They already asked us: sending one back means yes.
        if (db.getReference("friendRequests").child(me).child(them).get().await().exists()) {
            accept(them)
            return
        }
        // Already pending from us: nothing to do, unless we now have their invite token. Requests
        // can't be edited, so the old one is withdrawn and sent again with it.
        val pending = db.getReference("friendRequests").child(them).child(me).get().await()
        if (pending.exists()) {
            if (via == null || pending.child("via").string() == via) return
            cancel(them)
        }

        val theirProfile = SocialParser.profile(them, db.getReference("users").child(them).get().await())
            ?: throw FriendRequestException("No one is called @$name")
        val update = mapOf(
            "friendRequests/$them/$me" to mapOf(
                "fromUsername" to myProfile.username,
                "fromDisplayName" to myProfile.displayName,
                "toUsername" to theirProfile.username,
                "toDisplayName" to theirProfile.displayName,
                "sentAt" to ServerValue.TIMESTAMP,
            ) + listOfNotNull(via?.let { "via" to it }),
            "sentFriendRequests/$me/$them" to true,
        )
        try {
            root.updateChildren(update).await()
        } catch (e: Exception) {
            // We can't read their block list, so a denial here almost always means they blocked us.
            if (e.isPermissionDenied()) throw FriendRequestException("You can't send a request to @$name")
            throw e
        }
        push?.friendRequest(them)
    }

    override suspend fun accept(fromUid: String) {
        val me = session.requireUid()
        if (!db.getReference("friendRequests").child(me).child(fromUid).get().await().exists()) {
            throw FriendRequestException("That request is no longer available")
        }
        root.updateChildren(
            mapOf(
                "friends/$me/$fromUid" to mapOf("since" to ServerValue.TIMESTAMP),
                "friends/$fromUid/$me" to mapOf("since" to ServerValue.TIMESTAMP),
            ) + requestCleanup(me, fromUid),
        ).await()
    }

    override suspend fun decline(fromUid: String) {
        val me = session.requireUid()
        root.updateChildren(
            mapOf("friendRequests/$me/$fromUid" to null, "sentFriendRequests/$fromUid/$me" to null),
        ).await()
    }

    override suspend fun cancel(toUid: String) {
        val me = session.requireUid()
        root.updateChildren(
            mapOf("friendRequests/$toUid/$me" to null, "sentFriendRequests/$me/$toUid" to null),
        ).await()
    }

    override suspend fun unfriend(uid: String) {
        val me = session.requireUid()
        root.updateChildren(mapOf("friends/$me/$uid" to null, "friends/$uid/$me" to null)).await()
    }

    override suspend fun block(uid: String) {
        val me = session.requireUid()
        require(uid != me) { "You can't block yourself" }
        root.updateChildren(
            mapOf(
                "blocked/$me/$uid" to true,
                "friends/$me/$uid" to null,
                "friends/$uid/$me" to null,
                "joinRequests/$me/$uid" to null,
                "joinRequests/$uid/$me" to null,
                "invites/$me/$uid" to null,
                "invites/$uid/$me" to null,
            ) + requestCleanup(me, uid),
        ).await()
    }

    override suspend fun unblock(uid: String) {
        val me = session.requireUid()
        db.getReference("blocked").child(me).child(uid).removeValue().await()
    }

    override fun observeBlocked(): Flow<Set<String>> = session.uid.flatMapLatest { me ->
        if (me == null) {
            flowOf(emptySet())
        } else {
            db.getReference("blocked").child(me).snapshots().map { s -> s?.children?.mapNotNull { it.key }?.toSet().orEmpty() }
        }
    }

    /** Removes requests in both directions between [a] and [b], plus their sender indexes. */
    private fun requestCleanup(a: String, b: String): Map<String, Any?> = mapOf(
        "friendRequests/$a/$b" to null,
        "friendRequests/$b/$a" to null,
        "sentFriendRequests/$a/$b" to null,
        "sentFriendRequests/$b/$a" to null,
    )

    private fun <T> forUid(block: (String) -> Flow<List<T>>): Flow<List<T>> =
        session.uid.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else block(uid) }
}
