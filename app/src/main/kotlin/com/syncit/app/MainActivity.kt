package com.syncit.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.InviteHandoff
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.nav.SyncItApp
import com.syncit.app.ui.splash.StartupSplash
import com.syncit.app.ui.theme.SyncItTheme
import com.syncit.app.ui.theme.isDark
import com.syncit.core.config.FriendLinks
import com.syncit.core.config.RoomLinks
import com.syncit.media.MediaListenerService

class MainActivity : ComponentActivity() {

    /** Room code from an invite link (https or syncit://), consumed by navigation. */
    private val pendingJoinCode = mutableStateOf<String?>(null)

    /** A friend's personal invite link (FriendLinks), consumed by navigation. */
    private val pendingFriendInvite = mutableStateOf<FriendLinks.Invite?>(null)

    /** Passwordless sign-in link from the email (see the manifest's /__/auth/ filter). */
    private val pendingEmailLink = mutableStateOf<String?>(null)

    /** A tapped message or friend-request notification. */
    private val pendingNotification = mutableStateOf<PushNotifications.Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SyncItApplication).container
        // Opening the app undoes "Quit" (see SyncItApplication.quitApp).
        if (container.prefs.quit.value) {
            container.prefs.setQuit(false)
            MediaListenerService.rebind(this)
        }
        // Only the launching intent: after rotation/recreation it was already handled.
        if (savedInstanceState == null) handleLink(intent)
        // Opened from the link itself, so the clipboard fallback isn't needed.
        if (pendingJoinCode.value != null || pendingFriendInvite.value != null) container.prefs.inviteClipboardChecked = true
        // The startup animation: only on a fresh launch from the home screen, not from a link or notification.
        val playSplash = savedInstanceState == null && intent?.action == Intent.ACTION_MAIN
        val quickSplash = playSplash && !container.prefs.takeFullSplash(java.time.LocalDate.now().toEpochDay())
        setContent {
            val mode by container.prefs.themeMode.collectAsStateWithLifecycle()
            val dark = mode.isDark()
            var splash by rememberSaveable { mutableStateOf(playSplash) }
            // Status/navigation bar icons follow the app's theme, not the phone's (light on the black splash).
            val darkBars = dark || splash
            DisposableEffect(darkBars) {
                val style = if (darkBars) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SyncItTheme(dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    SyncItApp(
                        container = container,
                        pendingJoinCode = pendingJoinCode,
                        pendingFriendInvite = pendingFriendInvite,
                        pendingEmailLink = pendingEmailLink,
                        pendingNotification = pendingNotification,
                    )
                    if (splash) StartupSplash(quick = quickSplash, onDone = { splash = false })
                }
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
            takeLink(link)
        }
    }

    /** A room invite or a friend's invite link; anything else is ignored. */
    private fun takeLink(text: String) {
        RoomLinks.codeFromLink(text)?.let {
            pendingJoinCode.value = it
            return
        }
        FriendLinks.fromLink(text)?.let { pendingFriendInvite.value = it }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // First launch after installing from an invite page: the page left the link on the
        // clipboard. Checked once ever, and only once we have focus (required to read it).
        if (hasFocus && pendingJoinCode.value == null && pendingFriendInvite.value == null) {
            val prefs = (application as SyncItApplication).container.prefs
            InviteHandoff.takeFromClipboardOnce(this, prefs)?.let(::takeLink)
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a Notification Access grant made in system settings.
        (application as SyncItApplication).container.gateway.refresh()
    }
}
