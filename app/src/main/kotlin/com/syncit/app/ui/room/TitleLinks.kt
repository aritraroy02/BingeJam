package com.syncit.app.ui.room

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.URLEncoder
import com.syncit.core.model.RoomPreview
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

/**
 * Jams: open the host's exact item ([ref] = RoomPreview.playableRef, e.g. "spotify:track:…" or a
 * YouTube watch URL) in [service]'s own app; both open in-app and start that item. Falls back to
 * [openForPlay] (the host's link or just the app) when there's no usable ref. False if the app
 * isn't installed.
 */
internal fun openJamItem(context: Context, service: StreamingService, ref: String?, title: RoomTitle?): Boolean {
    val safe = MemberPrompts.safePlayableRef(service, ref)
    if (safe != null && service.isConfigured) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, safe.toUri()).setPackage(service.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return true
        } catch (_: ActivityNotFoundException) {
            // Not installed, or this version doesn't take the link: try the fallbacks.
        }
    }
    return openForPlay(context, service, title)
}

/**
 * The room's Play button: the host's link if it's for this service (opens the show page),
 * otherwise just the streaming app. False if the app isn't installed.
 */
internal fun openForPlay(context: Context, service: StreamingService, title: RoomTitle?): Boolean {
    if (title != null && title.service == service && openTitle(context, title)) return true
    // Needs <queries> for the package (manifest) on Android 11+.
    val launch = context.packageManager.getLaunchIntentForPackage(service.packageName) ?: return false
    return try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Netflix publishes no content ID, so "Play in Netflix" needs help to open the host's exact movie:
 * an ID looked up by title + length (TitleLookup: relay + Watchmode), then netflix.com/watch/<id>,
 * which starts it playing; the sync session then jumps to the room's position. Episodes are left
 * out: Netflix gives only the episode name, not the show's.
 */
internal object NetflixLinks {
    data class Query(val title: String, val durationMs: Long?)

    /** "S2:E4", "S1 E2", "s3e10". */
    private val EPISODE = Regex("""\bS\d+\s*:?\s*E\d+\b""", RegexOption.IGNORE_CASE)
    private val ID = Regex("""\d{1,12}""")

    /** What to look up for the host's Netflix preview, or null (not Netflix, no title, an episode). */
    fun query(preview: RoomPreview?): Query? {
        if (preview?.service != StreamingService.NETFLIX) return null
        val title = preview.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (listOfNotNull(preview.subtitle, title).any { EPISODE.containsMatchIn(it) }) return null
        return Query(title, preview.durationMs?.takeIf { it > 0 })
    }

    /** Starts the title in Netflix's player. Null unless [netflixId] is all digits. */
    fun watchUrl(netflixId: String?): String? =
        netflixId?.takeIf { ID.matches(it) }?.let { "https://www.netflix.com/watch/$it" }

    /** Netflix's search screen with [title] already typed in. */
    fun searchUrl(title: String): String =
        "https://www.netflix.com/search/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20")
}

/**
 * Netflix watch parties: the movie itself when we know its ID, else the host's link, else a
 * search for the movie, else the app. False if Netflix isn't installed.
 */
internal fun openNetflix(context: Context, netflixId: String?, query: NetflixLinks.Query?, title: RoomTitle?): Boolean {
    val service = StreamingService.NETFLIX
    NetflixLinks.watchUrl(netflixId)?.let { if (openInPackage(context, service, it)) return true }
    if (title != null && title.service == service && openTitle(context, title)) return true
    query?.let { if (openInPackage(context, service, NetflixLinks.searchUrl(it.title))) return true }
    return openForPlay(context, service, null)
}

private fun openInPackage(context: Context, service: StreamingService, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(service.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Netflix started cold drops the movie link: it closes its start-up screen about 2 s later and the
 * user lands back in the room (Motorola, Netflix 9.89, 2026-10-29). Already running, the same link
 * plays at once. So after "Play in Netflix", if the room screen is back in front within [windowMs]
 * and Netflix isn't playing on this phone, open the link once more: Netflix is running by then.
 * Times are SystemClock.elapsedRealtime().
 */
internal class NetflixRetry(private val windowMs: Long = WINDOW_MS) {
    private var openedAtMs: Long? = null

    /** "Play in Netflix" was tapped (not called for the retry itself, so there's only one). */
    fun opened(nowMs: Long) {
        openedAtMs = nowMs
    }

    /** The room screen is in front again. True at most once per tap. */
    fun shouldRetry(nowMs: Long, netflixPlayingHere: Boolean): Boolean {
        val at = openedAtMs ?: return false
        openedAtMs = null
        return !netflixPlayingHere && nowMs - at in 0..windowMs
    }

    companion object {
        const val WINDOW_MS = 12_000L
    }
}
