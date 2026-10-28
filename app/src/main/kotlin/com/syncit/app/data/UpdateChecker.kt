package com.syncit.app.data

import com.syncit.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app is installed from an APK, so nothing updates it automatically. Once per launch we
 * ask GitHub for the latest release of the public releases repo and compare versions.
 * Unauthenticated GitHub API: nothing about the user is sent beyond the request itself.
 */
object UpdateChecker {

    const val RELEASES_REPO = "aritraroy02/sync-it-releases"

    /** Always the newest release's APK; the invite site links here too. */
    const val DOWNLOAD_URL = "https://github.com/$RELEASES_REPO/releases/latest/download/sync-it.apk"

    data class Update(val version: String, val notes: String?)

    /** The newer release, or null if we're current or the check failed (offline, rate-limited). */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.github.com/repos/$RELEASES_REPO/releases/latest")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode != 200) return@runCatching null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val latest = json.optString("tag_name").removePrefix("v")
                if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                    Update(latest, json.optString("body").takeIf { it.isNotBlank() })
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Numeric comparison of dotted versions: 0.10.0 > 0.9.2. Non-numeric parts count as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
