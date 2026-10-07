package com.syncit.app.ui.room

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.syncit.core.model.RoomTitle
import com.syncit.core.model.StreamingService


/** Light validation of pasted share links. */
internal object TitleLinks {
    data class Check(val valid: Boolean, val warning: String?)

    fun check(service: StreamingService, link: String): Check {
        if (link.isBlank()) return Check(false, null)
        val uri = runCatching { link.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) {
            return Check(false, "Paste the full link starting with https://")
        }
        if (link.length > 500) return Check(false, "That link is too long")
        val host = uri.host!!.lowercase()
        val matches = service.linkHosts.any { host == it || host.endsWith(".$it") }
        return Check(true, if (matches) null else "This doesn't look like a ${service.displayName} link, but it'll be saved anyway.")
    }
}

/**
 * ACTION_VIEW on the share link. Try the official app directly first (when its package is
 * known); if that fails, let Android pick, which usually still routes to the app via its
 * App Links, else the browser.
 */
internal fun openTitle(context: Context, title: RoomTitle): Boolean {
    val view = Intent(Intent.ACTION_VIEW, title.deepLink.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (title.service.isConfigured) {
        try {
            context.startActivity(Intent(view).setPackage(title.service.packageName))
            return true
        } catch (_: ActivityNotFoundException) {
            // Not installed, or it doesn't handle this link: fall through.
        }
    }
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
