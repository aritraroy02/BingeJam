package com.syncit.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Our notification listener. Android only hands out other apps' MediaSessions
 * (MediaSessionManager.getActiveSessions) to apps with an enabled notification listener, so
 * this service exists purely to hold that grant.
 *
 * It deliberately does not override onNotificationPosted/onNotificationRemoved: we never
 * read notification content.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectionCallback?.invoke()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connectionCallback?.invoke()
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaListenerService::class.java)

        /** Set by the gateway so it can re-check access when the system (un)binds us. */
        @Volatile
        internal var connectionCallback: (() -> Unit)? = null
    }
}
