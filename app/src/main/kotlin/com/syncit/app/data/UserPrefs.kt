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

    /** Room code + when we entered it (wall clock), for the "In a room · 05:12" bar. */
    var roomJoinedAt: Pair<String, Long>?
        get() = prefs.getString(KEY_ROOM_JOINED_AT, null)?.split('|')?.takeIf { it.size == 2 }
            ?.let { (code, at) -> at.toLongOrNull()?.let { code to it } }
        set(value) = prefs.edit { putString(KEY_ROOM_JOINED_AT, value?.let { "${it.first}|${it.second}" }) }

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

    private val _quit = MutableStateFlow(prefs.getBoolean(KEY_QUIT, false))

    /**
     * The user tapped "Quit" and hasn't opened the app since. Presence and activity sharing stay
     * off even if something (a push message) starts the process in the background.
     */
    val quit: StateFlow<Boolean> = _quit.asStateFlow()

    fun setQuit(value: Boolean) {
        // commit(): quitApp() ends the process right after, so the write mustn't be deferred.
        prefs.edit(commit = true) { putBoolean(KEY_QUIT, value) }
        _quit.value = value
    }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    /**
     * Sharing choices are stored per account (keys suffixed with the uid), so a second account on
     * this phone never inherits the first one's. [bindAccount] switches which account they belong
     * to; signed out = share nothing.
     */
    private var boundUid: String? = null

    private val _sharedApps = MutableStateFlow<Set<ActivityApp>>(emptySet())

    /** Apps whose "now playing" friends may see, for the bound account. Empty (the default) = share nothing. */
    val sharedApps: StateFlow<Set<ActivityApp>> = _sharedApps.asStateFlow()

    private val _invisible = MutableStateFlow(false)

    /** Invisible: friends see us offline and no activity is shared, whatever [sharedApps] says. */
    val invisible: StateFlow<Boolean> = _invisible.asStateFlow()

    /** Called whenever the signed-in account changes (null = signed out). Synchronous. */
    @Synchronized
    fun bindAccount(uid: String?) {
        if (uid == boundUid) return
        boundUid = uid
        _sharedApps.value = if (uid == null) {
            emptySet()
        } else {
            prefs.getStringSet(KEY_SHARED_APPS + uid, emptySet()).orEmpty().mapNotNull(ActivityApp::fromWire).toSet()
        }
        _invisible.value = uid != null && prefs.getBoolean(KEY_INVISIBLE + uid, false)
    }

    /** Ignored while signed out (there's no account to store it for). */
    @Synchronized
    fun setSharedApps(apps: Set<ActivityApp>) {
        val uid = boundUid ?: return
        prefs.edit { putStringSet(KEY_SHARED_APPS + uid, apps.map { it.wire }.toSet()) }
        _sharedApps.value = apps
    }

    @Synchronized
    fun setInvisible(value: Boolean) {
        val uid = boundUid ?: return
        prefs.edit { putBoolean(KEY_INVISIBLE + uid, value) }
        _invisible.value = value
    }

    private val _activitySetupDone = MutableStateFlow(prefs.getStringSet(KEY_ACTIVITY_SETUP_UIDS, emptySet()).orEmpty().toSet())

    /** uids of the accounts that have completed the activity-sharing setup page on this phone. */
    val activitySetupDone: StateFlow<Set<String>> = _activitySetupDone.asStateFlow()

    @Synchronized
    fun markActivitySetupDone(uid: String) {
        val done = _activitySetupDone.value + uid
        prefs.edit { putStringSet(KEY_ACTIVITY_SETUP_UIDS, done) }
        _activitySetupDone.value = done
    }

    /**
     * The account [uid] was deleted: forget everything stored for it on this phone (sharing
     * choices, invisible, activity-setup flag) plus the account-wide leftovers (display name,
     * last/pending room). Phone-wide settings (theme, permissions walkthrough) stay.
     */
    @Synchronized
    fun clearAccount(uid: String) {
        val setupDone = _activitySetupDone.value - uid
        prefs.edit {
            remove(KEY_SHARED_APPS + uid)
            remove(KEY_INVISIBLE + uid)
            putStringSet(KEY_ACTIVITY_SETUP_UIDS, setupDone)
            remove(KEY_NAME)
            remove(KEY_LAST_ROOM)
            remove(KEY_PENDING_JOIN)
        }
        _activitySetupDone.value = setupDone
        if (boundUid == uid) {
            _sharedApps.value = emptySet()
            _invisible.value = false
        }
    }

    private companion object {
        const val KEY_NAME = "display_name"
        const val KEY_LAST_ROOM = "last_room"
        const val KEY_ROOM_JOINED_AT = "room_joined_at"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_PENDING_JOIN = "pending_join"
        const val KEY_CLIPBOARD_CHECKED = "invite_clipboard_checked"
        const val KEY_THEME = "theme_mode"
        const val KEY_QUIT = "quit"
        const val KEY_SHARED_APPS = "shared_apps_"
        const val KEY_INVISIBLE = "invisible_"
        const val KEY_ACTIVITY_SETUP_UIDS = "activity_setup_uids"
    }
}
