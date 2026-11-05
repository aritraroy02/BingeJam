package com.syncit.firebase.social

import android.util.Log
import com.syncit.firebase.FirebaseConfig
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the push relay (push-relay/, a Cloudflare Worker) to notify a friend's phones after we
 * wrote a message or friend request. The relay checks our Firebase ID token and reads the item
 * itself, so all we send is what to look up. Best effort: a failure only means no notification.
 * Does nothing while [FirebaseConfig.PUSH_RELAY_URL] is blank.
 */
internal class PushRelay(
    private val session: FirebaseSession,
    private val url: String = FirebaseConfig.PUSH_RELAY_URL,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun directMessage(toUid: String, messageId: String) =
        post(JSONObject().put("type", "dm").put("to", toUid).put("messageId", messageId))

    /** We deleted [messageId] for everyone: the relay replaces their notification if it still shows it. */
    fun messageDeleted(toUid: String, messageId: String) =
        post(JSONObject().put("type", "dm_deleted").put("to", toUid).put("messageId", messageId))

    fun friendRequest(toUid: String) =
        post(JSONObject().put("type", "friend_request").put("to", toUid))

    /** "Ask to join" / "Ask to jam": pops up on the host's phone even when the app is closed. */
    fun joinRequest(hostUid: String) =
        post(JSONObject().put("type", "join_request").put("to", hostUid))

    fun invite(toUid: String) =
        post(JSONObject().put("type", "invite").put("to", toUid))

    /** A group message: the relay notifies the other members (it reads the members and the message itself). */
    fun groupMessage(groupId: String, messageId: String) =
        post(JSONObject().put("type", "group_message").put("group", groupId).put("messageId", messageId))

    /** We invited [toUid] to a group (their setting is "Ask me first"). */
    fun groupInvite(groupId: String, toUid: String) =
        post(JSONObject().put("type", "group_invite").put("group", groupId).put("to", toUid))

    /** "Join the room": the relay checks we're both in the group and limits how often it's sent. */
    fun roomPing(groupId: String, toUid: String) =
        post(JSONObject().put("type", "room_ping").put("group", groupId).put("to", toUid))

    private fun post(body: JSONObject) {
        if (url.isBlank()) return
        val user = session.auth.currentUser?.takeUnless { it.isAnonymous } ?: return
        scope.launch {
            try {
                val idToken = user.getIdToken(false).await().token ?: return@launch
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 10_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $idToken")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = connection.responseCode
                    if (code !in 200..299) Log.w(TAG, "Push relay answered $code")
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Push relay request failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "SyncItPush"
    }
}
