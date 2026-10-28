package com.syncit.media

import android.content.ComponentName
import android.content.Context
import android.os.Build
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
        connected = this
        connectionCallback?.invoke()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (connected === this) connected = null
        connectionCallback?.invoke()
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        /** The bound instance, for [unbind] before Android 14 (the static requestUnbind is API 34). */
        @Volatile
        private var connected: MediaListenerService? = null

        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaListenerService::class.java)

        /**
         * "Quit": while Notification Access is on, Android keeps this service bound, which keeps the
         * app's process alive. Unbinding lets the process end; the grant itself stays on and the
         * system won't rebind until [rebind] (or a reboot).
         */
        fun unbind(context: Context) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    requestUnbind(componentName(context))
                } else {
                    connected?.requestUnbind()
                }
            }
        }

        /** Undo [unbind] when the user opens the app again. No-op without Notification Access. */
        fun rebind(context: Context) {
            runCatching { requestRebind(componentName(context)) }
        }

        /** Set by the gateway so it can re-check access when the system (un)binds us. */
        @Volatile
        internal var connectionCallback: (() -> Unit)? = null
    }
}
