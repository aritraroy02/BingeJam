package com.syncit.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import androidx.core.graphics.scale
import com.syncit.core.model.NowPlayingMetadata
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MediaMetadata → [NowPlayingMetadata]. Uses only what the app publishes for media controls
 * (title, subtitle, artwork bitmap). URIs are ignored: we never fetch another app's resources.
 */
internal object MetadataMapper {

    /** Longest side of the shared thumbnail. Big enough for the room's hero, small enough for RTDB. */
    private const val MAX_SIDE_PX = 480
    private const val TARGET_BYTES = 45_000

    fun toNowPlaying(metadata: MediaMetadata?): NowPlayingMetadata? {
        if (metadata == null) return null
        val description = metadata.description
        val title = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: description?.title?.toString()?.takeIf { it.isNotBlank() }
        val subtitle = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM)
            ?: description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: description?.iconBitmap
        return NowPlayingMetadata(title = title, subtitle = subtitle, artworkJpeg = bitmap?.let(::thumbnail))
            .takeUnless { it.isEmpty && subtitle == null }
    }

    private fun MediaMetadata.text(key: String): String? =
        getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** Scales down to [MAX_SIDE_PX] and JPEG-encodes, lowering quality until under [TARGET_BYTES]. */
    private fun thumbnail(source: Bitmap): ByteArray? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return try {
            val scale = MAX_SIDE_PX.toFloat() / max(source.width, source.height)
            val scaled = if (scale < 1f) {
                source.scale((source.width * scale).roundToInt(), (source.height * scale).roundToInt())
            } else {
                source
            }
            // Hardware bitmaps can't be compressed directly.
            val readable = if (scaled.config == Bitmap.Config.HARDWARE) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
            var quality = 80
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { out ->
                    readable.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                quality -= 15
            } while (bytes.size > TARGET_BYTES && quality >= 35)
            bytes.takeIf { it.size <= TARGET_BYTES }
        } catch (e: RuntimeException) {
            null
        }
    }
}
