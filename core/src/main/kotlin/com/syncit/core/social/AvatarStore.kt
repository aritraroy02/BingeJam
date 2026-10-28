package com.syncit.core.social

/**
 * Profile pictures, hosted for free next to the push relay (push-relay/, Cloudflare KV).
 * Upload returns a public URL that changes with every upload; save it with
 * [ProfileRepository.updatePhoto]. Implemented in :data-firebase.
 */
interface AvatarStore {
    /**
     * Uploads [bytes] as our picture and returns its URL. Still images up to [MAX_STILL_BYTES]
     * (the app sends a small square JPEG); GIFs up to [MAX_GIF_BYTES].
     * Throws [AvatarException] with a message for the user.
     */
    suspend fun upload(bytes: ByteArray): String

    suspend fun delete()

    companion object {
        const val MAX_GIF_BYTES = 1_048_576
        const val MAX_STILL_BYTES = 300_000
    }
}

class AvatarException(message: String) : Exception(message)
