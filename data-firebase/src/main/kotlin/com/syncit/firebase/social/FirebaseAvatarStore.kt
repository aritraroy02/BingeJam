package com.syncit.firebase.social

import com.syncit.core.social.AvatarException
import com.syncit.core.social.AvatarStore
import com.syncit.firebase.FirebaseConfig
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.NotSignedInException
import com.syncit.firebase.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** [AvatarStore] on the push-relay Worker (PUT/DELETE /avatar, authenticated with our ID token). */
class FirebaseAvatarStore internal constructor(
    private val session: FirebaseSession,
    private val baseUrl: String = FirebaseConfig.PUSH_RELAY_URL,
) : AvatarStore {

    override suspend fun upload(bytes: ByteArray): String {
        if (bytes.size > AvatarStore.MAX_GIF_BYTES) throw AvatarException("That picture is too big (max 1 MB)")
        val (code, body) = request("PUT", bytes)
        return when (code) {
            200 -> JSONObject(body).getString("url")
            413 -> throw AvatarException("That picture is too big (max 1 MB for GIFs)")
            415 -> throw AvatarException("That file isn't a picture we can use")
            429 -> throw AvatarException("Wait a few seconds before changing it again")
            else -> throw AvatarException("Upload failed ($code). Try again.")
        }
    }

    override suspend fun delete() {
        val (code, _) = request("DELETE", null)
        if (code !in 200..299) throw AvatarException("Couldn't remove it ($code). Try again.")
    }

    private suspend fun request(method: String, body: ByteArray?): Pair<Int, String> {
        if (baseUrl.isBlank()) throw AvatarException("Profile pictures aren't set up in this build")
        val user = session.auth.currentUser?.takeUnless { it.isAnonymous } ?: throw NotSignedInException()
        val idToken = user.getIdToken(false).await().token ?: throw NotSignedInException()
        return withContext(Dispatchers.IO) {
            val connection = URL("$baseUrl/avatar").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to text
            } finally {
                connection.disconnect()
            }
        }
    }
}
