package com.syncit.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.syncit.app.ui.nav.SyncItApp
import com.syncit.app.ui.theme.SyncItTheme
import com.syncit.core.config.RoomLinks

class MainActivity : ComponentActivity() {

    /** Room code from a syncit://join/CODE link, consumed by the Home screen. */
    private val pendingJoinCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingJoinCode.value = parseJoinCode(intent?.data)
        val container = (application as SyncItApplication).container
        setContent {
            SyncItTheme {
                SyncItApp(container = container, pendingJoinCode = pendingJoinCode)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseJoinCode(intent.data)?.let { pendingJoinCode.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a Notification Access grant made in system settings.
        (application as SyncItApplication).container.gateway.refresh()
    }

    private fun parseJoinCode(uri: Uri?): String? {
        if (uri == null || uri.scheme != RoomLinks.SCHEME || uri.host != RoomLinks.HOST) return null
        val code = uri.lastPathSegment?.uppercase() ?: return null
        return code.takeIf { it.length == RoomLinks.CODE_LENGTH && it.all { c -> c in RoomLinks.CODE_ALPHABET } }
    }
}
