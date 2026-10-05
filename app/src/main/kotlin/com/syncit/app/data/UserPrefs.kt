package com.syncit.app.data

import android.content.Context
import androidx.core.content.edit

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

    private companion object {
        const val KEY_NAME = "display_name"
        const val KEY_LAST_ROOM = "last_room"
        const val KEY_ONBOARDED = "onboarding_done"
    }
}
