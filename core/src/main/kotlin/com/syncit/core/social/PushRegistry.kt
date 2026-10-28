package com.syncit.core.social

/**
 * This phone's push token for the signed-in account (pushTokens/{uid}/{token}), so the push relay
 * can notify it about new messages and friend requests. Implemented in :data-firebase.
 */
interface PushRegistry {
    suspend fun register(token: String)

    /** Call while still signed in (the write needs the account). */
    suspend fun unregister(token: String)
}
