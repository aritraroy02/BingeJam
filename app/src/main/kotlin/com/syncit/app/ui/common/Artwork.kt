package com.syncit.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.theme.Glass

/** Decodes the shared JPEG thumbnail once per distinct byte array. Small (≤ 52 KB), so main thread is fine. */
@Composable
fun rememberArtwork(jpeg: ByteArray?): ImageBitmap? = remember(jpeg?.contentHashCode()) {
    jpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
}

/** The movie artwork with rounded corners and a glassy edge, at its own aspect ratio. */
@Composable
fun ArtworkCard(artwork: ImageBitmap, modifier: Modifier = Modifier, corner: Int = 22) {
    val p = Glass.palette
    val shape = RoundedCornerShape(corner.dp)
    Image(
        bitmap = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(artwork.width.toFloat() / artwork.height.coerceAtLeast(1))
            .clip(shape)
            .border(1.dp, p.hairline, shape),
    )
}
