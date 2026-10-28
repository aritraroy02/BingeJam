package com.syncit.core.social

import com.syncit.core.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Profiles and unique usernames. usernames/{username} → uid is claimed atomically (rules allow
 * creating it only if absent), so two users can never hold the same name. Implemented in
 * :data-firebase.
 */
interface ProfileRepository {
    /** Our profile; null until a username has been claimed. */
    fun observeMyProfile(): Flow<UserProfile?>

    fun observeProfile(uid: String): Flow<UserProfile?>

    /** Normalises (lowercase, trims) and checks format and availability. */
    suspend fun checkUsername(candidate: String): UsernameCheck

    /**
     * Up to [count] available usernames based on [seed] (e.g. the Google display name), mixing
     * name-based handles ("aritra.r", "aritra_42") with fun ones ("nightowl_aritra").
     */
    suspend fun suggestUsernames(seed: String?, count: Int = 6): List<String>

    /**
     * Claims [username] and creates or updates our profile. When renaming, the old name is
     * released in the same atomic update. Throws [UsernameTakenException] if someone got it first.
     */
    suspend fun claimUsername(username: String, displayName: String)

    suspend fun updateProfile(displayName: String, bio: String?)

    /** Sets (or with null removes) our profile picture URL, from [AvatarStore.upload]. */
    suspend fun updatePhoto(photoUrl: String?)

    /** Exact lookup by username (case-insensitive). */
    suspend fun findByUsername(username: String): UserProfile?

    /** Usernames starting with [prefix] (at least 2 chars), for search-as-you-type. */
    suspend fun searchByPrefix(prefix: String, limit: Int = 10): List<UserProfile>
}

sealed interface UsernameCheck {
    data class Available(val normalized: String) : UsernameCheck
    data object Taken : UsernameCheck
    data class Invalid(val reason: String) : UsernameCheck
}

class UsernameTakenException(username: String) : Exception("@$username is already taken")
