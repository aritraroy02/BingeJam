package com.syncit.app.data

import android.content.Context
import androidx.core.content.edit
import com.syncit.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small local settings. SharedPreferences is enough; no DataStore dependency needed. */
class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("syncit", Context.MODE_PRIVATE)

    var displayName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_NAME, value) }

    var lastRoomCode: String?
        get() = prefs.getString(KEY_LAST_ROOM, null)
        set(value) = prefs.edit { putString(KEY_LAST_ROOM, value) }

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    /** A room to join once onboarding is finished (from an invite link). Survives process death. */
    var pendingJoinCode: String?
        get() = prefs.getString(KEY_PENDING_JOIN, null)
        set(value) = prefs.edit { putString(KEY_PENDING_JOIN, value) }

    /** The first-launch clipboard check for an invite (see InviteHandoff) runs only once. */
    var inviteClipboardChecked: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_CHECKED, false)
        set(value) = prefs.edit { putBoolean(KEY_CLIPBOARD_CHECKED, value) }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    private companion object {
        const val KEY_NAME = "display_name"
        const val KEY_LAST_ROOM = "last_room"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_PENDING_JOIN = "pending_join"
        const val KEY_CLIPBOARD_CHECKED = "invite_clipboard_checked"
        const val KEY_THEME = "theme_mode"
    }
}
