package com.syncit.voice

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
import com.syncit.core.voice.VOICE_ECHO_ROOM
import com.syncit.core.voice.VoiceState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the mic allowed while the streaming app is in front (foreground service of type
 * microphone, Android 14+). The voice itself runs in [AndroidVoiceController]; this only holds
 * the notification, with Mute / Leave.
 */
class VoiceService : Service() {
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice chat", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you're in a room's voice chat"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = AndroidVoiceController.instance
        val state = controller?.state?.value
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state ?: VoiceState()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        when (intent?.action) {
            ACTION_MUTE -> controller?.setMuted(true)
            ACTION_UNMUTE -> controller?.setMuted(false)
            ACTION_LEAVE -> controller?.leave()
        }
        if (controller == null || state?.active != true) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.coroutineContext.cancelChildren()
        scope.launch {
            controller.state.distinctUntilChangedBy { listOf(it.muted, it.deafened, it.peers.size, it.status) }.collect { s ->
                if (s.active) notifyIfAllowed(buildNotification(s)) else stopSelf()
            }
        }
        // A restarted process has no voice session to come back to.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    private fun notifyIfAllowed(notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: VoiceState): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val others = state.peers.size
        val text = when {
            state.roomCode == VOICE_ECHO_ROOM -> "Testing your mic"
            others == 0 -> "Waiting for others to join voice"
            others == 1 -> "Talking with ${state.peers[0].name}"
            else -> "Talking with $others people"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (state.muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic)
            .setContentTitle(
                when {
                    state.deafened -> "${Brand.NAME} voice · deafened"
                    state.muted -> "${Brand.NAME} voice · muted"
                    else -> "${Brand.NAME} voice"
                },
            )
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(
                if (state.muted) R.drawable.ic_voice_mic else R.drawable.ic_voice_mic_off,
                if (state.muted) "Unmute" else "Mute",
                action(if (state.muted) ACTION_UNMUTE else ACTION_MUTE, 1),
            )
            .addAction(R.drawable.ic_voice_leave, "Leave voice", action(ACTION_LEAVE, 2))
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, VoiceService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "voice"
        private const val NOTIFICATION_ID = 4_201
        private const val ACTION_MUTE = "com.syncit.voice.MUTE"
        private const val ACTION_UNMUTE = "com.syncit.voice.UNMUTE"
        private const val ACTION_LEAVE = "com.syncit.voice.LEAVE"

        internal fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
