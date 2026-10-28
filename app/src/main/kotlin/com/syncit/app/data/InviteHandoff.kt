package com.syncit.app.data

import android.content.ClipboardManager
import android.content.Context
import com.syncit.core.config.RoomLinks

/**
 * Auto-join after installing from the invite page. Android can't pass a link through an APK
 * install, so the page copies the invite link to the clipboard when "Download" is tapped,
 * and the app looks for it exactly once, on its first launch.
 *
 * Only one of our invite links is used; anything else on the clipboard is ignored and nothing
 * is stored or sent. Android 12+ shows its own "pasted from clipboard" notice.
 */
object InviteHandoff {

    /**
     * Call when the activity has window focus (Android 10+ only lets the focused app read the
     * clipboard). Returns the room code, or null.
     */
    fun takeFromClipboardOnce(context: Context, prefs: UserPrefs): String? {
        if (prefs.inviteClipboardChecked) return null
        prefs.inviteClipboardChecked = true
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        for (i in 0 until clip.itemCount) {
            val text = runCatching { clip.getItemAt(i).coerceToText(context)?.toString() }.getOrNull()
            RoomLinks.codeFromLink(text)?.let { return it }
        }
        return null
    }
}
