package com.syncit.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.syncit.core.config.Brand
import com.syncit.core.config.RoomLinks

/**
 * Legal pages on the invite site (web/, Firebase Hosting). THE ONE PLACE their URLs live in the
 * app. The manifest only claims /join/ on this host as an App Link, so these open in the browser.
 */
object LegalLinks {
    private const val BASE = "https://${RoomLinks.WEB_HOST}"

    const val TERMS = "$BASE/terms"
    const val PRIVACY = "$BASE/privacy"

    /** What deleting an account removes, and how to ask for it without the app. */
    const val DELETE_ACCOUNT_HELP = "$BASE/delete-account"

    const val NOT_AFFILIATED =
        "${Brand.NAME} isn't affiliated with Netflix, JioHotstar, Amazon, Spotify or YouTube."

    /** Opens [url] in the browser. False if nothing on the phone can open a web page. */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
