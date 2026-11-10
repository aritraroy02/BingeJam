package com.syncit.core.config

/**
 * Personal invite links (2026-11-10): https://WEB_HOST/add/<username>/<token>. Opening one sends the
 * link's owner a friend request carrying the token, which is accepted at once (see FriendInvites).
 * The token is the secret part: knowing a username alone only gets an ordinary request.
 */
object FriendLinks {
    const val TOKEN_LENGTH = 12
    const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** The app scheme's host, for the invite page's "I already have BingeJam" button. */
    const val HOST = "add"

    data class Invite(val username: String, val token: String)

    fun shareLink(username: String, token: String): String = "https://${RoomLinks.WEB_HOST}/add/$username/$token"

    fun shareText(username: String, token: String): String =
        "Add me on ${Brand.NAME} and let's watch together: ${shareLink(username, token)}"

    private val linkPattern = Regex(
        """(?:${Regex.escape(RoomLinks.SCHEME)}://$HOST/|https?://${Regex.escape(RoomLinks.WEB_HOST)}/add/)""" +
            """([a-z0-9_][a-z0-9_.]{1,18}[a-z0-9_])/([A-Za-z0-9]{$TOKEN_LENGTH})(?![A-Za-z0-9])""",
    )

    /** The invite in [text] (https or syncit:// link, anywhere in the text), or null. */
    fun fromLink(text: String?): Invite? {
        val m = linkPattern.find(text ?: return null) ?: return null
        val (username, token) = m.destructured
        if (username.contains("..") || !token.all { it in TOKEN_ALPHABET }) return null
        return Invite(username, token)
    }
}
