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

    fun friendRequest(toUid: String) =
        post(JSONObject().put("type", "friend_request").put("to", toUid))

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
