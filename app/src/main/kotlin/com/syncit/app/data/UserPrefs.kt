package com.syncit.app.data

import android.content.Context
import androidx.core.content.edit
import com.syncit.app.ui.theme.ThemeMode
import com.syncit.core.model.ActivityApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small local settings. SharedPreferences is enough; no DataStore dependency needed. */
class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("syncit", Context.MODE_PRIVATE)

    /**
     * Name shown to others in rooms and room chat. Kept equal to our profile's display name
     * (AppViewModel copies it whenever the profile loads), so the room code and the chat bubble
     * service can read it without a network call.
     */
    var displayName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_NAME, value) }

    var lastRoomCode: String?
        get() = prefs.getString(KEY_LAST_ROOM, null)
        set(value) = prefs.edit { putString(KEY_LAST_ROOM, value) }

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))

    /** The permissions walkthrough (Notification Access etc.) has been finished once. */
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    var onboardingDone: Boolean
        get() = _onboarded.value
        set(value) {
            prefs.edit { putBoolean(KEY_ONBOARDED, value) }
            _onboarded.value = value
        }

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

    private val _sharedApps = MutableStateFlow(
        prefs.getStringSet(KEY_SHARED_APPS, emptySet()).orEmpty().mapNotNull(ActivityApp::fromWire).toSet(),
    )

    /** Apps whose "now playing" friends may see. Empty (the default) = share nothing. */
    val sharedApps: StateFlow<Set<ActivityApp>> = _sharedApps.asStateFlow()

    fun setSharedApps(apps: Set<ActivityApp>) {
        prefs.edit { putStringSet(KEY_SHARED_APPS, apps.map { it.wire }.toSet()) }
        _sharedApps.value = apps
    }

    private val _invisible = MutableStateFlow(prefs.getBoolean(KEY_INVISIBLE, false))

    /** Invisible: friends see us offline and no activity is shared, whatever [sharedApps] says. */
    val invisible: StateFlow<Boolean> = _invisible.asStateFlow()

    fun setInvisible(value: Boolean) {
        prefs.edit { putBoolean(KEY_INVISIBLE, value) }
        _invisible.value = value
    }

    private val _activitySetupFor = MutableStateFlow(prefs.getString(KEY_ACTIVITY_SETUP_UID, null))

    /** uid of the account that has seen the activity-sharing setup page on this phone. */
    val activitySetupFor: StateFlow<String?> = _activitySetupFor.asStateFlow()

    fun markActivitySetupDone(uid: String) {
        prefs.edit { putString(KEY_ACTIVITY_SETUP_UID, uid) }
        _activitySetupFor.value = uid
    }

    private companion object {
        const val KEY_NAME = "display_name"
        const val KEY_LAST_ROOM = "last_room"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_PENDING_JOIN = "pending_join"
        const val KEY_CLIPBOARD_CHECKED = "invite_clipboard_checked"
        const val KEY_THEME = "theme_mode"
        const val KEY_SHARED_APPS = "shared_apps"
        const val KEY_INVISIBLE = "invisible"
        const val KEY_ACTIVITY_SETUP_UID = "activity_setup_uid"
    }
}
