package com.syncit.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Purple = Color(0xFF4A2C8A)

@Composable
fun SyncItTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme(primary = Color(0xFFCBB8FF))
        else -> lightColorScheme(primary = Purple)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Status colours used by the presence dot and sync status card. */
object StatusColors {
    val Online = Color(0xFF2E7D32)
    val Offline = Color(0xFF9E9E9E)
    val Warning = Color(0xFFE65100)
}
