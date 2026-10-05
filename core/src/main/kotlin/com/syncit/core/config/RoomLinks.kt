package com.syncit.core.config

/** Room code format and the share link handed to friends. */
object RoomLinks {
    const val CODE_LENGTH = 6

    /** No 0/O or 1/I, so codes read out loud or typed by hand don't get mixed up. */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    const val SCHEME = "syncit"
    const val HOST = "join"

    // Open question: a custom scheme isn't clickable in every messenger. Swap for an https App
    // Link once there's a domain to host assetlinks.json on.
    fun shareLink(code: String): String = "$SCHEME://$HOST/$code"

    fun shareText(code: String): String =
        "Join my Sync it watch party! Room code: $code\n${shareLink(code)}"
}
