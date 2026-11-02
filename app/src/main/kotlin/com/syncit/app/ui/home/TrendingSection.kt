package com.syncit.app.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.PlatformBadge
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.room.NetflixLinks
import com.syncit.app.ui.room.openForPlay
import com.syncit.app.ui.theme.Glass
import com.syncit.core.lookup.TrendingRow
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService

/** "Trending on Netflix" etc.: a swipeable row of posters per platform, then TMDB/JustWatch credit. */
@Composable
internal fun TrendingSection(rows: List<TrendingRow>, onOpen: (TrendingTitle, StreamingService) -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        for (row in rows) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Trending on ${row.service.displayName}")
                // Bleed to the screen edges so posters scroll under the gutters.
                LazyRow(
                    modifier = Modifier.layout { measurable, constraints ->
                        val gutter = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + gutter * 2))
                        layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(row.titles, key = { it.tmdbId }) { title -> PosterCard(title) { onOpen(title, row.service) } }
                }
            }
        }
        Text(
            "Movie data from TMDB · Where to watch by JustWatch",
            style = MaterialTheme.typography.labelSmall,
            color = p.textSecondary,
        )
    }
}

@Composable
private fun PosterCard(title: TrendingTitle, onClick: () -> Unit) {
    val p = Glass.palette
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(title.title, title.year?.toString()).joinToString(", ") }
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Poster(title, Modifier.width(120.dp).height(180.dp).clip(shape))
        Spacer(Modifier.height(4.dp))
        Text(
            title.title,
            style = MaterialTheme.typography.labelLarge,
            color = p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        title.year?.let {
            Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
private fun Poster(title: TrendingTitle, modifier: Modifier) {
    val p = Glass.palette
    Box(modifier.background(p.glassFallback), contentAlignment = Alignment.Center) {
        if (title.posterUrl != null) {
            AsyncImage(model = title.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(title.title, style = MaterialTheme.typography.labelMedium, color = p.textSecondary, modifier = Modifier.padding(8.dp))
        }
        title.rating?.takeIf { it > 0 }?.let { rating ->
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(2.dp))
                Text("%.1f".format(rating), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

/** The tapped poster: watch it with friends (a room, then invites) or alone. */
@Composable
internal fun TrendingSheetContent(
    title: TrendingTitle,
    service: StreamingService,
    busy: Boolean,
    onWithFriends: () -> Unit,
    onAlone: () -> Unit,
) {
    val p = Glass.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Poster(title, Modifier.width(84.dp).height(126.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlatformBadge(service)
            Text(title.title, style = MaterialTheme.typography.titleLarge, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(title.year?.toString(), title.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
                .takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.textSecondary) }
        }
    }
    Spacer(Modifier.height(4.dp))
    if (busy) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = p.accent, strokeWidth = 2.dp)
        }
    } else {
        GlassButton("Watch with friends", onClick = onWithFriends, icon = Icons.Filled.PlayArrow, modifier = Modifier.fillMaxWidth())
        GlassButton(
            "Watch alone in ${service.displayName}",
            onClick = onAlone,
            style = GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        "Watch with friends starts a room and lets you invite them. Everyone needs their own ${service.displayName} subscription.",
        style = MaterialTheme.typography.bodySmall,
        color = p.textSecondary,
    )
}

/**
 * Opens [link] (the title's own page/player) in [service]'s app; without one, Netflix's search for
 * the title, else just the app. False if the app isn't installed.
 */
internal fun openTrending(context: Context, service: StreamingService, link: String?, title: TrendingTitle): Boolean {
    val target = link ?: if (service == StreamingService.NETFLIX) NetflixLinks.searchUrl(title.title) else null
    if (target != null) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()).setPackage(service.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Not installed, or this version doesn't take the link: try just the app.
        }
    }
    return openForPlay(context, service, null)
}
