package com.syncit.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.syncit.app.SyncItApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * FCM entry point. Messages from push-relay/ carry a notification payload, so in the background
 * Android shows them itself; this only runs for messages that arrive while the app is open.
 */
class VyroMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data[PushNotifications.EXTRA_TYPE] ?: return
        val uid = message.data[PushNotifications.EXTRA_UID] ?: return
        val title = message.notification?.title ?: return
        PushNotifications.show(this, type, uid, title, message.notification?.body.orEmpty())
    }

    override fun onNewToken(token: String) {
        val social = (application as SyncItApplication).container.social
        if (social.auth.currentUid == null) return
        scope.launch { runCatching { social.push.register(token) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
