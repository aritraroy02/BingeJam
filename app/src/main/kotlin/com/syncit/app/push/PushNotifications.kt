package com.syncit.app.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.syncit.app.BuildConfig
import com.syncit.app.MainActivity
import com.syncit.app.R
import com.syncit.app.data.UpdateChecker
import com.syncit.core.social.PushRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phone notifications for new messages, friend requests, join requests and room invites (and new
 * versions, see [showUpdate]). They're sent by push-relay/ through
 * FCM. In the background Android shows them from the FCM payload; in the foreground
 * [VyroMessagingService] shows them here. Either way a tap opens MainActivity with [EXTRA_TYPE]
 * and [EXTRA_UID], which navigation turns into the chat or the Friends screen.
 */
object PushNotifications {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_FRIENDS = "friends"

    /** Join requests and room invites: high importance, so they pop up (heads-up) at once. */
    const val CHANNEL_REQUESTS = "requests"

    /** Announcements sent from the Firebase console (Messaging → New campaign). */
    const val CHANNEL_NEWS = "news"

    // Keys and values match the relay's FCM data payload.
    const val EXTRA_TYPE = "type"
    const val EXTRA_UID = "uid"
    const val TYPE_DM = "dm"
    const val TYPE_FRIEND_REQUEST = "friend_request"
    const val TYPE_JOIN_REQUEST = "join_request"
    const val TYPE_INVITE = "invite"
    private val TYPES = setOf(TYPE_DM, TYPE_FRIEND_REQUEST, TYPE_JOIN_REQUEST, TYPE_INVITE)

    /** Same id as FCM uses, so the tag alone decides what replaces what. */
    private const val NOTIFICATION_ID = 0

    /** The chat on screen right now; its messages don't need a notification. */
    @Volatile
    var openChatUid: String? = null

    data class Target(val type: String, val uid: String)

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Messages from friends" },
                NotificationChannel(CHANNEL_FRIENDS, "Friend requests", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "People who want to be friends" },
                NotificationChannel(CHANNEL_REQUESTS, "Join requests & invites", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Friends asking to join you, or inviting you" },
                NotificationChannel(CHANNEL_NEWS, "News & updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Announcements about the app" },
            ),
        )
    }

    // Guarded by areNotificationsEnabled(); POST_NOTIFICATIONS is asked for during onboarding.
    @SuppressLint("MissingPermission")
    fun show(context: Context, type: String, uid: String, title: String, body: String) {
        if (type == TYPE_DM && uid == openChatUid) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TYPE, type)
            .putExtra(EXTRA_UID, uid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            context, tag(type, uid).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = when (type) {
            TYPE_DM -> CHANNEL_MESSAGES
            TYPE_JOIN_REQUEST, TYPE_INVITE -> CHANNEL_REQUESTS
            else -> CHANNEL_FRIENDS
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_vyro)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (type == TYPE_DM) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(if (channel == CHANNEL_FRIENDS) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager.notify(tag(type, uid), NOTIFICATION_ID, notification)
    }

    /**
     * An announcement that arrived while the app is open (in the background Android shows console
     * campaigns itself, on the default channel from the manifest, which is [CHANNEL_NEWS]).
     */
    @SuppressLint("MissingPermission")
    fun showNews(context: Context, id: String, title: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_NEWS)
            .setSmallIcon(R.drawable.ic_stat_vyro)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager.notify("news-$id", NOTIFICATION_ID, notification)
    }

    /** FCM topic the relay announces new releases on (push-relay/ announceRelease). */
    const val TOPIC_UPDATES = "updates"
    const val TYPE_UPDATE = "update"

    /** Every install listens for new-version announcements. Idempotent; best effort. */
    fun subscribeToUpdates() {
        if (!UpdateChecker.enabled) return
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_UPDATES) }
    }

    /**
     * "BingeJam 0.6.1 is available" (announced by the relay for every release). Shown only if
     * [version] is newer than this install; a tap opens the app, whose Home banner downloads it.
     */
    @SuppressLint("MissingPermission")
    fun showUpdate(context: Context, version: String, notes: String?) {
        if (!UpdateChecker.enabled || !UpdateChecker.isNewer(version, BuildConfig.VERSION_NAME)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = notes?.trim()?.takeIf { it.isNotEmpty() } ?: "Tap to get the new version."
        val notification = NotificationCompat.Builder(context, CHANNEL_NEWS)
            .setSmallIcon(R.drawable.ic_stat_vyro)
            .setContentTitle(context.getString(R.string.app_name) + " " + version + " is available")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager.notify(TYPE_UPDATE, NOTIFICATION_ID, notification)
    }

    /** Clears the notification for a chat once it's opened. */
    fun cancelChat(context: Context, uid: String) {
        NotificationManagerCompat.from(context).cancel(tag(TYPE_DM, uid), NOTIFICATION_ID)
    }

    /** Where a notification tap wants to go, from the launching intent's extras. */
    fun target(intent: Intent?): Target? {
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: return null
        val uid = intent.getStringExtra(EXTRA_UID)?.takeIf { it.isNotBlank() } ?: return null
        return if (type in TYPES) Target(type, uid) else null
    }

    /** Same tag as the relay's FCM payload ("type-uid"). */
    private fun tag(type: String, uid: String) = "$type-$uid"

    /** Saves this phone's FCM token for the signed-in account. Best effort. */
    suspend fun registerToken(registry: PushRegistry) {
        val token = currentToken() ?: return
        safely { registry.register(token) }
    }

    /**
     * Before signing out: forget this phone's token on the account and rotate it, so the next
     * account on this phone doesn't get the previous one's notifications.
     */
    suspend fun unregisterToken(registry: PushRegistry) {
        val token = currentToken() ?: return
        safely { registry.unregister(token) }
        safely { FirebaseMessaging.getInstance().deleteToken().await() }
    }

    /**
     * After the account was deleted: rotate this phone's token so nothing addressed to the old
     * account can reach it. (The eraser already removed it from the account.)
     */
    suspend fun deleteLocalToken() {
        safely { FirebaseMessaging.getInstance().deleteToken().await() }
    }

    private suspend fun currentToken(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No notification for this phone; nothing else depends on it.
        }
    }
}
