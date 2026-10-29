package com.syncit.app.ui.common

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Big enough for a 48dp icon on a 3x screen. */
private const val ICON_PX = 144

/**
 * Icons of the streaming/music apps, taken from the copy installed on this phone (so we ship no
 * brand artwork, and it always matches what the user sees on their home screen). Only hits are
 * cached: an app installed later shows up next time. Touched from composition only (main thread).
 */
private val iconCache = HashMap<String, ImageBitmap>()

/**
 * The installed app's launcher icon (already in the phone's icon shape), or null when it isn't
 * installed or not visible to us (AndroidManifest <queries> lists the supported apps).
 */
@Composable
fun rememberInstalledAppIcon(packageName: String): ImageBitmap? {
    val pm = LocalContext.current.packageManager
    return remember(packageName) {
        if (packageName.isBlank()) return@remember null
        iconCache[packageName] ?: try {
            pm.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { iconCache[packageName] = it }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}

/** The app's real icon, or [fallback] (e.g. the coloured name chip) when it isn't installed. */
@Composable
fun InstalledAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    fallback: @Composable () -> Unit,
) {
    val icon = rememberInstalledAppIcon(packageName)
    if (icon != null) {
        // Decorative: the app's name is always written next to it.
        Image(icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        fallback()
    }
}
