package com.syncit.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.InviteHandoff
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.nav.SyncItApp
import com.syncit.app.ui.theme.SyncItTheme
import com.syncit.app.ui.theme.isDark
import com.syncit.core.config.RoomLinks

class MainActivity : ComponentActivity() {

    /** Room code from an invite link (https or syncit://), consumed by navigation. */
    private val pendingJoinCode = mutableStateOf<String?>(null)

    /** Passwordless sign-in link from the email (see the manifest's /__/auth/ filter). */
    private val pendingEmailLink = mutableStateOf<String?>(null)

    /** A tapped message or friend-request notification. */
    private val pendingNotification = mutableStateOf<PushNotifications.Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SyncItApplication).container
        // Only the launching intent: after rotation/recreation it was already handled.
        if (savedInstanceState == null) handleLink(intent)
        // Opened from the link itself, so the clipboard fallback isn't needed.
        if (pendingJoinCode.value != null) container.prefs.inviteClipboardChecked = true
        setContent {
            val mode by container.prefs.themeMode.collectAsStateWithLifecycle()
            val dark = mode.isDark()
            // Status/navigation bar icons follow the app's theme, not the phone's.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SyncItTheme(dark = dark) {
                SyncItApp(
                    container = container,
                    pendingJoinCode = pendingJoinCode,
                    pendingEmailLink = pendingEmailLink,
                    pendingNotification = pendingNotification,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** VIEW intents: an email sign-in link (AuthService decides) or a room invite link. */
    private fun handleLink(intent: Intent?) {
        PushNotifications.target(intent)?.let {
            pendingNotification.value = it
            return
        }
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString() ?: return
        val auth = (application as SyncItApplication).container.social.auth
        if (auth.isEmailSignInLink(link)) {
            pendingEmailLink.value = link
        } else {
            RoomLinks.codeFromLink(link)?.let { pendingJoinCode.value = it }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // First launch after installing from an invite page: the page left the link on the
        // clipboard. Checked once ever, and only once we have focus (required to read it).
        if (hasFocus && pendingJoinCode.value == null) {
            val prefs = (application as SyncItApplication).container.prefs
            InviteHandoff.takeFromClipboardOnce(this, prefs)?.let { pendingJoinCode.value = it }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a Notification Access grant made in system settings.
        (application as SyncItApplication).container.gateway.refresh()
    }
}
