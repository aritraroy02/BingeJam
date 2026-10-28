package com.syncit.core.model

/**
 * What a streaming app's MediaSession says is playing (MediaMetadata title/subtitle/artwork).
 * This is session metadata the app publishes for media controls, not notification content.
 *
 * [artworkJpeg] is a small JPEG thumbnail made on the device (a few tens of KB), or null.
 */
class NowPlayingMetadata(
    val title: String?,
    val subtitle: String?,
    val artworkJpeg: ByteArray?,
    /** The app's own ID for what's playing (MediaMetadata METADATA_KEY_MEDIA_ID), if it publishes one. */
    val mediaId: String? = null,
    /** Length of what's playing (METADATA_KEY_DURATION), if known. Tells episodes with similar titles apart. */
    val durationMs: Long? = null,
    /** Every key the app published, as "key = value" (bitmaps as their size). Diagnostics only. */
    val allFields: List<String> = emptyList(),
    /**
     * A public reference that opens this exact item in the same app, e.g. "spotify:track:<id>" or
     * "https://www.youtube.com/watch?v=<id>". Set only when the app publishes one in a known public
     * format (see :media); never a private stream/CDN URL (JioHotstar's MEDIA_URI is one).
     */
    val playableRef: String? = null,
) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && artworkJpeg == null

    override fun equals(other: Any?): Boolean =
        other is NowPlayingMetadata && title == other.title && subtitle == other.subtitle &&
            mediaId == other.mediaId && durationMs == other.durationMs && playableRef == other.playableRef &&
            artworkJpeg.contentEquals(other.artworkJpeg)

    override fun hashCode(): Int =
        listOf(title, subtitle, mediaId, durationMs, playableRef).hashCode() * 31 + artworkJpeg.contentHashCode()

    override fun toString(): String =
        "NowPlayingMetadata(title=$title, subtitle=$subtitle, mediaId=$mediaId, artwork=${artworkJpeg?.size ?: 0} B)"
}

/**
 * previews/{code}: what someone with the room code sees before joining, and the room's
 * "now playing" hero. Written by the host; [service]/[title]/[subtitle]/[artworkJpeg] come from
 * the host's streaming app and are null until the host starts playing. [updatedAt] is server time.
 */
class RoomPreview(
    val code: String,
    val hostName: String,
    val service: StreamingService?,
    val title: String?,
    val subtitle: String?,
    val artworkJpeg: ByteArray?,
    val updatedAt: Long,
    val durationMs: Long? = null,
    /** Host's [NowPlayingMetadata.playableRef]: lets jam members start the exact same track. */
    val playableRef: String? = null,
) {
    /**
     * Is [local] the same title/episode the host is playing? Streaming apps don't publish a shared
     * content ID, so compare what they do publish: the title (ignoring case and spacing) and, when
     * both sides know it, the length (within [DURATION_TOLERANCE_MS]). Null = can't tell (no title
     * on one side), which callers treat as "don't block syncing".
     */
    fun matches(local: NowPlayingMetadata?): Boolean? {
        val expected = title?.normalized()?.takeIf { it.isNotEmpty() } ?: return null
        val actual = local?.title?.normalized()?.takeIf { it.isNotEmpty() } ?: return null
        if (expected != actual) return false
        val a = durationMs?.takeIf { it > 0 } ?: return true
        val b = local.durationMs?.takeIf { it > 0 } ?: return true
        return kotlin.math.abs(a - b) <= DURATION_TOLERANCE_MS
    }

    private fun String.normalized(): String = trim().lowercase().replace(WHITESPACE, " ")

    companion object {
        const val DURATION_TOLERANCE_MS = 10_000L
        private val WHITESPACE = Regex("""\s+""")
    }

    override fun equals(other: Any?): Boolean =
        other is RoomPreview && code == other.code && hostName == other.hostName && service == other.service &&
            title == other.title && subtitle == other.subtitle && updatedAt == other.updatedAt &&
            durationMs == other.durationMs && playableRef == other.playableRef &&
            artworkJpeg.contentEquals(other.artworkJpeg)

    override fun hashCode(): Int =
        listOf(code, hostName, service, title, subtitle, updatedAt, durationMs, playableRef).hashCode() * 31 +
            artworkJpeg.contentHashCode()
}
