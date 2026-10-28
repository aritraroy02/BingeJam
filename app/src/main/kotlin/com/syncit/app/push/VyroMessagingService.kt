package com.syncit.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.syncit.app.R
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
        val notification = message.notification ?: return
        val body = notification.body.orEmpty()
        val type = message.data[PushNotifications.EXTRA_TYPE]
        val uid = message.data[PushNotifications.EXTRA_UID]
        if (type != null && uid != null) {
            PushNotifications.show(this, type, uid, notification.title ?: return, body)
        } else {
            // A Firebase-console announcement: no type/uid in its data.
            PushNotifications.showNews(this, message.messageId ?: "latest", notification.title ?: getString(R.string.app_name), body)
        }
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
