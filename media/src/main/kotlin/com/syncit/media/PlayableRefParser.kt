package com.syncit.media

import com.syncit.core.config.StreamingConfig

/**
 * Derives [com.syncit.core.model.NowPlayingMetadata.playableRef]: a PUBLIC reference that opens the
 * exact item in the same app, so jam members can start the host's track. Pure (no Android) so the
 * patterns are unit-tested.
 *
 * Inputs are the session's own MediaMetadata MEDIA_ID and, for the jam apps below only, MEDIA_URI.
 * Never notification content. Only strict, known public formats are accepted; anything else
 * (including private stream/CDN URLs such as JioHotstar's MEDIA_URI) gives null.
 *
 * What these apps actually put in MEDIA_ID / MEDIA_URI is UNVERIFIED on a real device; if they
 * publish something else, playableRef stays null and jams fall back to title matching + search.
 *
 * Output formats (must stay in line with :data-firebase PlayableRefs and the database rule):
 *  - Spotify:       spotify:track:<22 base62> or spotify:episode:<22 base62>
 *  - YouTube:       https://www.youtube.com/watch?v=<11 char id>
 *  - YouTube Music: https://music.youtube.com/watch?v=<11 char id>
 *  - Amazon Music and every other app: null (no known public format).
 */
internal object PlayableRefParser {

    private val SPOTIFY = Regex("""spotify:(track|episode):[A-Za-z0-9]{22}""")
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    /** youtube.com / www. / m. / music. watch URL with a v= parameter anywhere in the query. */
    private val YOUTUBE_WATCH = Regex(
        """https?://(?:(?:www|m|music)\.)?youtube\.com/watch\?(?:[^#]*&)?v=([A-Za-z0-9_-]{11})(?:[&#].*)?""",
    )
    private val YOUTU_BE = Regex("""https?://youtu\.be/([A-Za-z0-9_-]{11})(?:[?#].*)?""")

    /** True for the packages whose MEDIA_URI may be read (jam apps with a public format). */
    fun readsMediaUri(packageName: String): Boolean = packageName in URI_PACKAGES

    /**
     * The playable reference for [packageName], from [mediaId] first, then [mediaUri]. Null when
     * neither is in a known public format for that app.
     */
    fun parse(packageName: String, mediaId: String?, mediaUri: String?): String? {
        val candidates = if (readsMediaUri(packageName)) listOf(mediaId, mediaUri) else listOf(mediaId)
        return candidates.firstNotNullOfOrNull { candidate -> candidate?.trim()?.let { normalize(packageName, it) } }
    }

    /**
     * [ref] if it's a valid, normalised reference for [packageName], else null. Used before
     * handing a host's ref (read from Firebase) to the member's app.
     */
    fun validFor(packageName: String, ref: String?): String? =
        ref?.takeIf { normalize(packageName, it) == it }

    private fun normalize(packageName: String, value: String): String? {
        if (value.isEmpty() || value.length > MAX_INPUT_LENGTH) return null
        return when (packageName) {
            StreamingConfig.SPOTIFY_PACKAGE -> value.takeIf { SPOTIFY.matches(it) }
            StreamingConfig.YOUTUBE_PACKAGE -> videoId(value)?.let { "https://www.youtube.com/watch?v=$it" }
            StreamingConfig.YOUTUBE_MUSIC_PACKAGE -> videoId(value)?.let { "https://music.youtube.com/watch?v=$it" }
            else -> null
        }
    }

    /** A bare 11-char video id, or the id from a youtube.com / youtu.be watch URL. */
    private fun videoId(value: String): String? {
        if (VIDEO_ID.matches(value)) return value
        YOUTUBE_WATCH.matchEntire(value)?.let { return it.groupValues[1] }
        YOUTU_BE.matchEntire(value)?.let { return it.groupValues[1] }
        return null
    }

    private const val MAX_INPUT_LENGTH = 500

    private val URI_PACKAGES = setOf(
        StreamingConfig.SPOTIFY_PACKAGE,
        StreamingConfig.YOUTUBE_PACKAGE,
        StreamingConfig.YOUTUBE_MUSIC_PACKAGE,
    )
}
