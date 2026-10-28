package com.syncit.media.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.syncit.core.config.Brand
import com.syncit.core.config.SyncStatusLabels
import com.syncit.core.session.AppQuitHandler
import com.syncit.core.session.JamFollow
import com.syncit.core.session.SyncDependenciesProvider
import com.syncit.core.session.SyncSessionState
import com.syncit.media.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the MediaController attached and the room connection alive while the user is in a
 * room and the streaming app is in front. Type mediaPlayback, since its whole job is keeping
 * media playback in sync. Shows one persistent, low-importance notification with a Stop action.
 */
class SyncForegroundService : Service() {

    private val scope = MainScope()
    private var coordinator: RoomSyncCoordinator? = null
    private var roomCode: String? = null
    private var lastNotificationText: String? = null
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // "Back to the jam" from the UI (AndroidSyncSessionController.followHost); main thread.
        scope.launch { SyncSessionStateHolder.followRequests.collect { coordinator?.followHost() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_QUIT) {
            stopSelf()
            (application as? AppQuitHandler)?.quitApp()
            return START_NOT_STICKY
        }

        val code = intent?.getStringExtra(EXTRA_ROOM_CODE)
        val deps = (application as? SyncDependenciesProvider)?.syncDependencies
        if (code == null || deps == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        roomCode = code
        // Must be called promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Connecting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )

        val c = coordinator ?: RoomSyncCoordinator(
            deps = deps,
            scope = scope,
            publish = { state ->
                SyncSessionStateHolder.state.value = state
                updateNotification(state)
            },
        ).also { coordinator = it }
        c.start(code)

        // If the system kills us, come back with the same room.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        // The system has already removed our foreground notification. coordinator.stop() publishes
        // an IDLE state; without this flag that would re-post it as a plain notification that
        // outlives the service ("room ABC123 · Not in a room").
        destroyed = true
        coordinator?.stop()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        coordinator = null
        scope.cancel()
        SyncSessionStateHolder.state.value = SyncSessionState()
        super.onDestroy()
    }

    private fun updateNotification(state: SyncSessionState) {
        if (destroyed) return
        val appName = state.service?.displayName ?: "Streaming app"
        val text = when (state.jamFollow) {
            JamFollow.STARTING -> getString(R.string.sync_jam_starting)
            JamFollow.NEEDS_APP -> getString(R.string.sync_jam_needs_app, appName)
            JamFollow.FAILED -> getString(R.string.sync_jam_failed)
            JamFollow.IDLE -> SyncStatusLabels.label(state.status, appName)
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        notifyIfAllowed(buildNotification(text))
    }

    // Guarded by areNotificationsEnabled(); POST_NOTIFICATIONS is requested during onboarding.
    @SuppressLint("MissingPermission")
    private fun notifyIfAllowed(notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this,
            2,
            Intent(this, SyncForegroundService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(getString(R.string.sync_notification_title, Brand.NAME, roomCode.orEmpty()))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.sync_notification_stop), stop)
            .addAction(0, getString(R.string.sync_notification_quit, Brand.NAME), quit)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.sync_channel_description, Brand.NAME) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.syncit.media.START"
        private const val ACTION_STOP = "com.syncit.media.STOP"
        private const val ACTION_QUIT = "com.syncit.media.QUIT"
        private const val EXTRA_ROOM_CODE = "room_code"

        fun startIntent(context: Context, roomCode: String): Intent =
            Intent(context, SyncForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOM_CODE, roomCode)
    }
}
