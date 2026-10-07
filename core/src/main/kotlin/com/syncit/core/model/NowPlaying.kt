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
) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && artworkJpeg == null

    override fun equals(other: Any?): Boolean =
        other is NowPlayingMetadata && title == other.title && subtitle == other.subtitle &&
            artworkJpeg.contentEquals(other.artworkJpeg)

    override fun hashCode(): Int = (title.hashCode() * 31 + subtitle.hashCode()) * 31 + artworkJpeg.contentHashCode()

    override fun toString(): String = "NowPlayingMetadata(title=$title, subtitle=$subtitle, artwork=${artworkJpeg?.size ?: 0} B)"
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
) {
    override fun equals(other: Any?): Boolean =
        other is RoomPreview && code == other.code && hostName == other.hostName && service == other.service &&
            title == other.title && subtitle == other.subtitle && updatedAt == other.updatedAt &&
            artworkJpeg.contentEquals(other.artworkJpeg)

    override fun hashCode(): Int =
        listOf(code, hostName, service, title, subtitle, updatedAt).hashCode() * 31 + artworkJpeg.contentHashCode()
}
