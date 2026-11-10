package com.syncit.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand

/** The product name in the accent gradient. The name comes from Brand (it will change). */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.displaySmall) {
    val p = Glass.palette
    Text(Brand.NAME, modifier = modifier, style = style.copy(color = p.textPrimary))
}
