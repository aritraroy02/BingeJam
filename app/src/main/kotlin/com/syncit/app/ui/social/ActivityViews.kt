package com.syncit.app.ui.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.syncit.app.ui.common.InstalledAppIcon
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.PlatformBadge
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.PlatformColors
import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp

/** Brand colour for an activity app's chip and artwork placeholder. */
fun ActivityApp.color(): Color = when (this) {
    ActivityApp.NETFLIX -> PlatformColors.Netflix
    ActivityApp.JIOHOTSTAR -> PlatformColors.JioHotstarStart
    ActivityApp.PRIME_VIDEO -> PlatformColors.PrimeVideo
    ActivityApp.SPOTIFY -> PlatformColors.Spotify
    ActivityApp.YOUTUBE_MUSIC, ActivityApp.YOUTUBE -> PlatformColors.YouTube
    ActivityApp.AMAZON_MUSIC -> PlatformColors.AmazonMusic
}

/**
 * Just the app's real icon (from the installed app), for rows that already show its name; the
 * name chip when it isn't installed.
 */
@Composable
fun ActivityAppIcon(app: ActivityApp, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    InstalledAppIcon(app.packageName, modifier, size) {
        // Not installed: an icon-sized tile in the brand colour with the initial, so the names
        // in a list still line up.
        val c = app.color()
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.24f))
                .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.displayName.take(1),
                color = if (app == ActivityApp.SPOTIFY || app == ActivityApp.AMAZON_MUSIC) PlatformColors.OnLightBrand else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.45f).sp,
            )
        }
    }
}

/**
 * Icon + name for apps we sync (all of them since jams), a plain coloured chip otherwise.
 * [onArtwork]: drawn over movie artwork, so the name is white.
 */
@Composable
fun ActivityAppBadge(app: ActivityApp, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    val service = app.syncService
    if (service != null) {
        PlatformBadge(service, modifier, onArtwork)
    } else {
        Text(
            app.displayName,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = modifier.clip(RoundedCornerShape(6.dp)).background(app.color()).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Square artwork thumbnail; a gradient tile with the app's initial when there's no artwork. */
@Composable
fun ActivityThumb(activity: Activity, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val p = Glass.palette
    val shape = RoundedCornerShape(12.dp)
    val art = rememberArtwork(activity.artworkJpeg)
    val edge = Modifier.border(1.dp, Brush.linearGradient(listOf(p.strokeTop, p.strokeBottom)), shape)
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(shape).then(edge))
    } else {
        val c = activity.app.color()
        Box(
            modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f)))).then(edge),
            contentAlignment = Alignment.Center,
        ) {
            Text(activity.app.displayName.take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.4f).sp)
        }
    }
}
