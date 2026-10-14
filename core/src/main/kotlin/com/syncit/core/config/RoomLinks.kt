package com.syncit.core.config

/** Room code format and the invite link handed to friends. */
object RoomLinks {
    const val CODE_LENGTH = 6

    /** No 0/O or 1/I, so codes read out loud or typed by hand don't get mixed up. */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /**
     * Firebase Hosting site of project syncit-watchparty (web/ in the repo). Its
     * /.well-known/assetlinks.json lets Android open /join/CODE links straight in the app.
     */
    const val WEB_HOST = "syncit-watchparty.web.app"

    /** Older invites and the clipboard handoff use syncit://join/CODE. */
    const val SCHEME = "syncit"
    const val HOST = "join"

    /** https, so it's clickable in every messenger. Opens the app if installed, else the invite page. */
    fun shareLink(code: String): String = "https://$WEB_HOST/join/$code"

    fun shareText(code: String): String =
        "Join my ${Brand.NAME} watch party! Room code: $code\n${shareLink(code)}"

    private val linkPattern = Regex(
        """(?:${Regex.escape(SCHEME)}://${Regex.escape(HOST)}/|https?://${Regex.escape(WEB_HOST)}/join/)([A-Za-z0-9]{$CODE_LENGTH})(?![A-Za-z0-9])""",
    )

    /**
     * The room code from an invite link (https://WEB_HOST/join/CODE or syncit://join/CODE),
     * found anywhere in [text], or null. Used for link intents and the clipboard handoff.
     */
    fun codeFromLink(text: String?): String? {
        val code = linkPattern.find(text ?: return null)?.groupValues?.get(1)?.uppercase() ?: return null
        return code.takeIf { c -> c.all { it in CODE_ALPHABET } }
    }
}
