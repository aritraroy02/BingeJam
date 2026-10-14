package com.syncit.app.di

import com.syncit.app.social.UsernameRules
import com.syncit.core.auth.AuthService
import com.syncit.core.auth.EmailRequiredException
import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.AuthState
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.FriendRequest
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.Presence
import com.syncit.core.model.RoomInvite
import com.syncit.core.model.SignInProvider
import com.syncit.core.model.UserProfile
import com.syncit.core.social.ActivityTracker
import com.syncit.core.social.ActivityUpdate
import com.syncit.core.social.DirectMessages
import com.syncit.core.social.FriendRequestException
import com.syncit.core.social.FriendsRepository
import com.syncit.core.social.PresenceRepository
import com.syncit.core.social.ProfileRepository
import com.syncit.core.social.UsernameCheck
import com.syncit.core.social.UsernameTakenException
import com.syncit.core.social.WatchInvites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory stand-ins for every v2 social interface, with a few sample friends and their activity,
 * so the UI can be built and clicked through before :data-firebase and :media ship the real ones.
 * Nothing leaves the phone and nothing survives a restart. Used only behind the switch in
 * [AppContainer].
 *
 * Simulated behaviour: an email sign-in link "arrives" 2.5 s after it's requested; friends reply
 * to DMs; asking to join is declined after a few seconds (a fake host can't open a real room).
 */
object FakeSocial {

    const val ME = "fake-me"

    /** [now] = server time (the transport's clock), so "Last seen" maths uses the same base as real data. */
    fun create(now: () -> Long): SocialServices {
        val store = Store(now)
        return SocialServices(
            auth = store.auth,
            profiles = store.profilesRepo,
            friends = store.friendsRepo,
            dms = store.dmsRepo,
            presence = store.presenceRepo,
            watchInvites = store.invites,
            activityTracker = store.tracker,
            isFake = true,
        )
    }

    private class Store(val now: () -> Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val profiles = MutableStateFlow(
            listOf(
                UserProfile("u-riya", "riya", "Riya Sen", bio = "Witcher marathon this weekend"),
                UserProfile("u-kabir", "kabir.m", "Kabir Mehta"),
                UserProfile("u-meera", "meera_k", "Meera Kapoor"),
                UserProfile("u-arjun", "arjun", "Arjun Nair"),
                UserProfile("u-zoe", "zoe.w", "Zoe Williams"),
                UserProfile("u-dev", "dev.patel", "Dev Patel"),
                UserProfile("u-ana", "ananya", "Ananya Iyer"),
            ).associateBy { it.uid },
        )

        val presence = MutableStateFlow(
            mapOf(
                "u-riya" to Presence(
                    online = true,
                    lastSeen = now(),
                    activity = Activity(
                        ActivityApp.NETFLIX, "Redanian Intelligence", "S2:E4", null, playing = true,
                        durationMs = 3_480_000, startedAt = now() - 600_000, roomCode = null,
                    ),
                ),
                "u-kabir" to Presence(
                    online = true,
                    lastSeen = now(),
                    activity = Activity(
                        ActivityApp.SPOTIFY, "Blinding Lights", "The Weeknd", null, playing = true,
                        durationMs = 200_000, startedAt = now() - 60_000, roomCode = null,
                    ),
                ),
                "u-meera" to Presence(online = true, lastSeen = now(), activity = null),
                "u-arjun" to Presence(online = false, lastSeen = now() - 5 * 60_000, activity = null),
                "u-ana" to Presence(
                    online = true,
                    lastSeen = now(),
                    activity = Activity(
                        ActivityApp.YOUTUBE, "Lo-fi beats to study to", null, null, playing = false,
                        durationMs = null, startedAt = now() - 120_000, roomCode = null,
                    ),
                ),
            ),
        )

        val friendSince = MutableStateFlow(
            mapOf("u-riya" to now() - 86_400_000L, "u-kabir" to now(), "u-meera" to now(), "u-arjun" to now(), "u-ana" to now()),
        )
        val incoming = MutableStateFlow(
            listOf(FriendRequest("u-zoe", ME, "zoe.w", "Zoe Williams", "", "", now() - 3_600_000)),
        )
        val outgoing = MutableStateFlow<List<FriendRequest>>(emptyList())
        val blocked = MutableStateFlow<Set<String>>(emptySet())

        val messages = MutableStateFlow(
            mapOf(
                "u-riya" to listOf(
                    DirectMessage("m1", "u-riya", "Starting Witcher S2 tonight, want in?", now() - 900_000),
                    DirectMessage("m2", "u-riya", "Episode 4 already 😅", now() - 300_000),
                ),
            ),
        )
        val reads = MutableStateFlow<Map<String, Long>>(emptyMap())

        val joinRequestsToMe = MutableStateFlow<List<JoinRequest>>(emptyList())
        val myJoinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
        val roomInvites = MutableStateFlow<List<RoomInvite>>(emptyList())

        val me: UserProfile? get() = profiles.value[ME]

        fun profile(uid: String) = profiles.value[uid]

        // ---- Auth ----

        val auth = object : AuthService {
            private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
            override val state: StateFlow<AuthState> = _state.asStateFlow()
            override val currentUid: String? get() = (state.value as? AuthState.SignedIn)?.uid
            override var pendingEmail: String? = null
                private set

            fun signIn(email: String?, name: String?, provider: SignInProvider) {
                _state.value = AuthState.SignedIn(ME, email, name, null, provider)
            }

            override suspend fun signInWithGoogleIdToken(idToken: String) {
                delay(600)
                signIn("you@example.com", "Alex Sharma", SignInProvider.GOOGLE)
            }

            override suspend fun sendEmailSignInLink(email: String) {
                delay(500)
                pendingEmail = email
                // Pretend the user tapped the link in their inbox.
                scope.launch {
                    delay(2_500)
                    if (_state.value is AuthState.SignedOut) signIn(email, null, SignInProvider.EMAIL_LINK)
                }
            }

            override fun isEmailSignInLink(link: String): Boolean = "/__/auth/" in link

            override suspend fun completeEmailLinkSignIn(link: String, email: String?) {
                val address = email ?: pendingEmail ?: throw EmailRequiredException()
                delay(400)
                signIn(address, null, SignInProvider.EMAIL_LINK)
            }

            override suspend fun signOut() {
                _state.value = AuthState.SignedOut
            }
        }

        // ---- Profiles ----

        val profilesRepo = object : ProfileRepository {
            override fun observeMyProfile(): Flow<UserProfile?> = profiles.map { it[ME] }

            override fun observeProfile(uid: String): Flow<UserProfile?> = profiles.map { it[uid] }

            override suspend fun checkUsername(candidate: String): UsernameCheck {
                delay(250)
                val name = UsernameRules.normalize(candidate)
                UsernameRules.problem(name)?.let { return UsernameCheck.Invalid(it) }
                return if (taken(name)) UsernameCheck.Taken else UsernameCheck.Available(name)
            }

            private fun taken(name: String) = profiles.value.values.any { it.username == name && it.uid != ME }

            override suspend fun suggestUsernames(seed: String?, count: Int): List<String> {
                delay(200)
                val parts = seed.orEmpty().lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                val first = parts.firstOrNull() ?: "viewer"
                val last = parts.drop(1).firstOrNull()
                val ideas = listOfNotNull(
                    if (last != null) "$first.$last" else null,
                    if (last != null) "${first}_${last.first()}" else null,
                    first,
                    "${first}_${(10..99).random()}",
                    "nightowl_$first",
                    "popcorn.$first",
                    "${first}watches",
                )
                return ideas.map { it.take(UsernameRules.MAX_LENGTH) }
                    .filter { UsernameRules.problem(it) == null && !taken(it) }
                    .distinct()
                    .take(count)
            }

            override suspend fun claimUsername(username: String, displayName: String) {
                delay(400)
                if (taken(username)) throw UsernameTakenException(username)
                profiles.update {
                    it + (ME to (it[ME] ?: UserProfile(ME, username, displayName, createdAt = now()))
                        .copy(username = username, displayName = displayName))
                }
            }

            override suspend fun updateProfile(displayName: String, bio: String?) {
                delay(300)
                profiles.update { map -> map[ME]?.let { map + (ME to it.copy(displayName = displayName, bio = bio)) } ?: map }
            }

            override suspend fun findByUsername(username: String): UserProfile? =
                profiles.value.values.firstOrNull { it.username == UsernameRules.normalize(username) }

            override suspend fun searchByPrefix(prefix: String, limit: Int): List<UserProfile> {
                delay(250)
                val p = UsernameRules.normalize(prefix)
                if (p.length < 2) return emptyList()
                return profiles.value.values.filter { it.uid != ME && it.username.startsWith(p) }
                    .sortedBy { it.username }.take(limit)
            }
        }

        // ---- Friends ----

        val friendsRepo = object : FriendsRepository {
            override fun observeFriends(): Flow<List<Friend>> =
                combine(friendSince, profiles, presence) { since, profileMap, presenceMap ->
                    since.mapNotNull { (uid, at) -> profileMap[uid]?.let { Friend(it, at, presenceMap[uid]) } }
                }

            override fun observeIncomingRequests(): Flow<List<FriendRequest>> = incoming
            override fun observeOutgoingRequests(): Flow<List<FriendRequest>> = outgoing

            override suspend fun sendRequest(toUsername: String) {
                delay(300)
                val target = profilesRepo.findByUsername(toUsername)
                    ?: throw FriendRequestException("No one has the username @${UsernameRules.normalize(toUsername)}")
                when {
                    target.uid == ME -> throw FriendRequestException("That's you")
                    target.uid in friendSince.value -> throw FriendRequestException("You're already friends")
                    target.uid in blocked.value -> throw FriendRequestException("Unblock @${target.username} first")
                }
                if (incoming.value.any { it.fromUid == target.uid }) return accept(target.uid)
                val mine = me
                outgoing.update { list ->
                    list.filterNot { it.toUid == target.uid } + FriendRequest(
                        ME, target.uid, mine?.username.orEmpty(), mine?.displayName.orEmpty(),
                        target.username, target.displayName, now(),
                    )
                }
            }

            override suspend fun accept(fromUid: String) {
                incoming.update { list -> list.filterNot { it.fromUid == fromUid } }
                friendSince.update { it + (fromUid to now()) }
            }

            override suspend fun decline(fromUid: String) = incoming.update { list -> list.filterNot { it.fromUid == fromUid } }
            override suspend fun cancel(toUid: String) = outgoing.update { list -> list.filterNot { it.toUid == toUid } }
            override suspend fun unfriend(uid: String) = friendSince.update { it - uid }

            override suspend fun block(uid: String) {
                unfriend(uid)
                incoming.update { list -> list.filterNot { it.fromUid == uid } }
                outgoing.update { list -> list.filterNot { it.toUid == uid } }
                blocked.update { it + uid }
            }

            override suspend fun unblock(uid: String) = blocked.update { it - uid }
            override fun observeBlocked(): Flow<Set<String>> = blocked
        }

        // ---- DMs ----

        val dmsRepo = object : DirectMessages {
            private var nextId = 0

            override fun observe(friendUid: String, limit: Int): Flow<List<DirectMessage>> =
                messages.map { it[friendUid].orEmpty().takeLast(limit) }

            override suspend fun send(friendUid: String, text: String) {
                add(friendUid, DirectMessage("local-${nextId++}", ME, text, now()))
                scope.launch {
                    delay(1_500)
                    add(friendUid, DirectMessage("reply-${nextId++}", friendUid, "😄", now()))
                }
            }

            private fun add(friendUid: String, message: DirectMessage) =
                messages.update { it + (friendUid to it[friendUid].orEmpty() + message) }

            override fun observeUnreadCounts(): Flow<Map<String, Int>> = combine(messages, reads) { all, read ->
                all.mapValues { (uid, list) -> list.count { it.fromUid != ME && it.sentAt > (read[uid] ?: 0L) } }
                    .filterValues { it > 0 }
            }

            override suspend fun markRead(friendUid: String) = reads.update { it + (friendUid to now()) }
        }

        // ---- Presence + activity ----

        val presenceRepo = object : PresenceRepository {
            override fun start() = Unit
            override fun stop() = Unit
            override suspend fun publishActivity(update: ActivityUpdate?) = Unit
        }

        val tracker = object : ActivityTracker {
            override fun start(sharedApps: StateFlow<Set<ActivityApp>>, currentRoomCode: StateFlow<String?>) = Unit
            override fun stop() = Unit
            override val current: StateFlow<Activity?> = MutableStateFlow(null)
        }

        // ---- Join requests + invites ----

        val invites = object : WatchInvites {
            override suspend fun requestToJoin(hostUid: String, app: ActivityApp, title: String?) {
                delay(300)
                val mine = me
                val request = JoinRequest(
                    hostUid, ME, mine?.username.orEmpty(), mine?.displayName.orEmpty(),
                    app, title, JoinStatus.PENDING, null, now(),
                )
                myJoinRequests.update { list -> list.filterNot { it.hostUid == hostUid } + request }
                scope.launch {
                    delay(4_000)
                    myJoinRequests.update { list ->
                        list.map { if (it.hostUid == hostUid) it.copy(status = JoinStatus.DECLINED) else it }
                    }
                }
            }

            override fun observeIncomingJoinRequests(): Flow<List<JoinRequest>> = joinRequestsToMe
            override fun observeMyJoinRequests(): Flow<List<JoinRequest>> = myJoinRequests

            override suspend fun acceptJoin(fromUid: String, roomCode: String) =
                joinRequestsToMe.update { list -> list.filterNot { it.fromUid == fromUid } }

            override suspend fun declineJoin(fromUid: String) =
                joinRequestsToMe.update { list -> list.filterNot { it.fromUid == fromUid } }

            override suspend fun clearMyJoinRequest(hostUid: String) =
                myJoinRequests.update { list -> list.filterNot { it.hostUid == hostUid } }

            override suspend fun inviteToRoom(friendUid: String, roomCode: String, app: ActivityApp?, title: String?) {
                delay(300)
            }

            override fun observeInvites(): Flow<List<RoomInvite>> = roomInvites

            override suspend fun dismissInvite(fromUid: String) =
                roomInvites.update { list -> list.filterNot { it.fromUid == fromUid } }
        }
    }
}
