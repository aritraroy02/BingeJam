package com.syncit.firebase.social

import android.net.Uri
import android.util.Log
import com.syncit.core.lookup.TitleLookup
import com.syncit.firebase.FirebaseConfig
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks the relay (push-relay/, GET /netflix) for a movie's Netflix ID. The relay looks it up in
 * Watchmode's catalogue and caches it; we also remember answers for this process so reopening the
 * room screen doesn't ask again.
 */
internal class RelayTitleLookup(
    private val session: FirebaseSession,
    private val baseUrl: String = FirebaseConfig.PUSH_RELAY_URL,
) : TitleLookup {
    private val answers = ConcurrentHashMap<String, String>() // key -> id, or "" for "not found"

    override suspend fun netflixId(title: String, durationMs: Long?): String? {
        if (baseUrl.isBlank() || title.isBlank()) return null
        val key = "$title|${durationMs ?: 0}"
        answers[key]?.let { return it.ifEmpty { null } }
        val user = session.auth.currentUser?.takeUnless { it.isAnonymous } ?: return null
        return try {
            val idToken = user.getIdToken(false).await().token ?: return null
            val id = withContext(Dispatchers.IO) { fetch(idToken, title, durationMs) }
            answers[key] = id.orEmpty()
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Netflix lookup failed", e) // not remembered: retried next time
            null
        }
    }

    private fun fetch(idToken: String, title: String, durationMs: Long?): String? {
        val url = Uri.parse(baseUrl).buildUpon()
            .appendPath("netflix")
            .appendQueryParameter("title", title)
            .apply { if (durationMs != null && durationMs > 0) appendQueryParameter("durationMs", durationMs.toString()) }
            .build().toString()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            if (connection.responseCode !in 200..299) error("relay answered ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // Only digits ever reach a URL we open.
            return JSONObject(body).optString("netflixId").takeIf { NETFLIX_ID.matches(it) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "SyncItLookup"
        val NETFLIX_ID = Regex("""\d{1,12}""")
    }
}
