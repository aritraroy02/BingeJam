package com.syncit.firebase.social

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.model.UserProfile
import com.syncit.core.social.BirthDateState
import com.syncit.core.social.ProfileRepository
import com.syncit.core.social.UsernameCheck
import com.syncit.core.social.UsernameTakenException
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.isPermissionDenied
import com.syncit.firebase.snapshots
import com.syncit.firebase.snapshotsOrError
import com.syncit.firebase.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Profiles (users/{uid}) and unique usernames (usernames/{key} → uid, key = [UsernameRules.key]:
 * the username with '.' stored as ',', because database keys can't contain '.').
 *
 * Uniqueness comes from the rules: usernames/{name} can only be created if absent, and only in
 * the same atomic update that points our profile at it. A rename releases the old name in that
 * same update, so nobody can ever hold two names or share one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseProfileRepository internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : ProfileRepository {

    private val root: DatabaseReference = db.reference
    private val users: DatabaseReference = db.getReference("users")
    private val usernames: DatabaseReference = db.getReference("usernames")

    /**
     * Our profile, or null while signed out or when no username has been claimed yet.
     *
     * Unlike [observeProfile], a failed read is an error, not null: if the listener is cancelled
     * (e.g. "permission denied", rules not published, token problems) the flow throws the
     * DatabaseException. "null" must only mean "this account has no profile", because the app
     * sends a null profile to the pick-a-username screen; an existing user must see an error
     * instead. Collectors should catch and offer a retry (re-collecting re-attaches the listener).
     */
    override fun observeMyProfile(): Flow<UserProfile?> =
        session.uid.flatMapLatest { uid ->
            if (uid == null) flowOf(null) else users.child(uid).snapshotsOrError().map { SocialParser.profile(uid, it) }
        }

    override fun observeProfile(uid: String): Flow<UserProfile?> =
        users.child(uid).snapshots().map { SocialParser.profile(uid, it) }

    override suspend fun checkUsername(candidate: String): UsernameCheck {
        val name = UsernameRules.normalize(candidate)
        UsernameRules.invalidReason(name)?.let { return UsernameCheck.Invalid(it) }
        val owner = usernames.child(UsernameRules.key(name)).get().await().string()
        return if (owner == null || owner == session.currentUid) UsernameCheck.Available(name) else UsernameCheck.Taken
    }

    override suspend fun suggestUsernames(seed: String?, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val me = session.currentUid
        val candidates = UsernameRules.suggestionCandidates(seed, max = count * 3)
        // One parallel round of direct reads (usernames/{name} is readable by any signed-in user).
        val available = coroutineScope {
            candidates.map { name ->
                async {
                    val owner = runCatching { usernames.child(UsernameRules.key(name)).get().await().string() }.getOrNull()
                    name.takeIf { owner == null || owner == me }
                }
            }.awaitAll()
        }
        return available.filterNotNull().take(count)
    }

    override suspend fun claimUsername(username: String, displayName: String) {
        val uid = session.requireUid()
        val name = UsernameRules.normalize(username)
        UsernameRules.invalidReason(name)?.let { throw IllegalArgumentException(it) }
        val cleanName = cleanDisplayName(displayName)

        val existing = users.child(uid).get().await()
        val oldName = existing.child("username").string()
        val profilePath = "users/$uid"
        val update = buildMap<String, Any?> {
            if (oldName != name) {
                put("usernames/${UsernameRules.key(name)}", uid)
                // Released in the same atomic write; the rules refuse a rename that keeps it.
                if (oldName != null) put("usernames/${UsernameRules.key(oldName)}", null)
            }
            put("$profilePath/username", name)
            put("$profilePath/displayName", cleanName)
            if (!existing.hasChild("photoUrl")) {
                session.auth.currentUser?.photoUrl?.toString()
                    ?.takeIf { it.startsWith("https://") && it.length <= MAX_PHOTO_URL_LENGTH }
                    ?.let { put("$profilePath/photoUrl", it) }
            }
            if (!existing.hasChild("createdAt")) put("$profilePath/createdAt", ServerValue.TIMESTAMP)
        }
        try {
            root.updateChildren(update).await()
        } catch (e: Exception) {
            if (!e.isPermissionDenied()) throw e
            // Denied: most likely someone else claimed it between our check and the write.
            val owner = runCatching { usernames.child(UsernameRules.key(name)).get().await().string() }.getOrNull()
            if (owner != null && owner != uid) throw UsernameTakenException(name)
            throw e
        }
    }

    override suspend fun updateProfile(displayName: String, bio: String?) {
        val uid = session.requireUid()
        if (!users.child(uid).child("username").get().await().exists()) {
            throw IllegalStateException("Pick a username first")
        }
        users.child(uid).updateChildren(
            mapOf(
                "displayName" to cleanDisplayName(displayName),
                // null deletes the field.
                "bio" to bio?.trim()?.take(MAX_BIO_LENGTH)?.takeIf { it.isNotEmpty() },
            ),
        ).await()
    }

    override suspend fun updatePhoto(photoUrl: String?) {
        val uid = session.requireUid()
        // null deletes the field; the rules only accept our own avatar URL or a Google photo.
        users.child(uid).child("photoUrl").setValue(photoUrl).await()
    }

    override suspend fun findByUsername(username: String): UserProfile? {
        val name = UsernameRules.normalize(username)
        if (!UsernameRules.isValid(name)) return null
        val uid = usernames.child(UsernameRules.key(name)).get().await().string() ?: return null
        return loadProfile(uid)?.takeIf { it.username == name }
    }

    override suspend fun searchByPrefix(prefix: String, limit: Int): List<UserProfile> {
        val p = UsernameRules.normalize(prefix)
        if (!UsernameRules.isSearchablePrefix(p) || limit <= 0) return emptyList()
        // The rules only allow key-ordered queries with limitToFirst ≤ 20 on usernames.
        val snapshot = usernames.orderByKey()
            .startAt(UsernameRules.key(p))
            .endAt(UsernameRules.key(p) + "\uf8ff")
            .limitToFirst(limit.coerceAtMost(MAX_SEARCH_RESULTS))
            .get().await()
        val hits = snapshot.children.mapNotNull { child -> child.string()?.let { uid -> child.key?.let { UsernameRules.fromKey(it) to uid } } }
        return coroutineScope {
            hits.map { (name, uid) ->
                async { runCatching { loadProfile(uid) }.getOrNull()?.takeIf { it.username == name } }
            }.awaitAll()
        }.filterNotNull()
    }

    private suspend fun loadProfile(uid: String): UserProfile? =
        SocialParser.profile(uid, users.child(uid).get().await())

    /** users_private/{uid}/dob: readable by us and the owner only; format checked by the rules. */
    override fun observeMyBirthDate(): Flow<BirthDateState> = session.uid.flatMapLatest { uid ->
        if (uid == null) {
            flowOf(BirthDateState.Loading)
        } else {
            db.getReference("users_private").child(uid).child("dob").snapshotsOrError().map { s ->
                s.string()?.let { BirthDateState.Set(it) } ?: BirthDateState.Missing
            }.catch { emit(BirthDateState.Loading) }
        }
    }

    override suspend fun setBirthDate(date: String) {
        require(BIRTH_DATE.matches(date)) { "Pick your date of birth" }
        val uid = session.requireUid()
        db.getReference("users_private").child(uid).child("dob").setValue(date).await()
    }

    companion object {
        private val BIRTH_DATE = Regex("^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
        const val MAX_DISPLAY_NAME_LENGTH = 40
        const val MAX_BIO_LENGTH = 160
        const val MAX_SEARCH_RESULTS = 20
        private const val MAX_PHOTO_URL_LENGTH = 500

        internal fun cleanDisplayName(displayName: String): String {
            val trimmed = displayName.trim().replace(Regex("\\s+"), " ").take(MAX_DISPLAY_NAME_LENGTH).trim()
            require(trimmed.isNotEmpty()) { "Enter a name" }
            return trimmed
        }
    }
}
