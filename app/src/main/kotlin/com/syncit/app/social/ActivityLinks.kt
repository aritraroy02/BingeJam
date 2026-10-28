package com.syncit.app.social

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import java.net.URLEncoder

/**
 * "Open in Spotify/YouTube/…": a secondary action on a friend's music or video activity (the
 * primary one is "Ask to jam"). Opens a search, since presence doesn't carry the exact item.
 */
object ActivityLinks {

    /**
     * A search for [query] inside [app], or null where the app has no search link we know of
     * (Amazon Music: we just open the app). %20 for spaces, which both Spotify URIs and the web
     * search URLs accept.
     */
    fun searchUri(app: ActivityApp, query: String): String? {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return when (app) {
            ActivityApp.SPOTIFY -> "spotify:search:$q"
            ActivityApp.YOUTUBE -> "https://www.youtube.com/results?search_query=$q"
            ActivityApp.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=$q"
            else -> null
        }
    }

    /**
     * Opens the friend's app on a search for what they're playing: first in that app (its
     * package from ActivityApp), then any app that handles the link, then just the app.
     * False if nothing could be opened (app not installed and no handler).
     */
    fun open(context: Context, activity: Activity): Boolean {
        val app = activity.app
        val uri = ActivityText.searchQuery(activity)?.let { searchUri(app, it) }
        val candidates = buildList {
            if (uri != null) {
                val view = Intent(Intent.ACTION_VIEW, uri.toUri())
                add(Intent(view).setPackage(app.packageName))
                // Spotify URIs only make sense in Spotify; web searches can fall back to a browser.
                if (!uri.startsWith("spotify:")) add(view)
            }
            // Needs <queries> for the package on Android 11+ (manifest).
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let(::add)
        }
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Not installed or doesn't handle this link; try the next option.
            }
        }
        return false
    }
}
