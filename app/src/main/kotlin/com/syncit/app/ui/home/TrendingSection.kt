package com.syncit.app.ui.home

import com.syncit.core.lookup.TrendingFilters
import com.syncit.core.lookup.TrendingFilter
import com.syncit.core.lookup.TitleType
import com.syncit.app.ui.glass.rememberReduceMotion
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.glass.ChipShape
import com.syncit.app.ui.glass.raised
import com.syncit.app.ui.glass.GlassSegmented
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import com.syncit.app.data.WatchlistItem
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

/**
 * "Trending now": Movies / Web series, genre and language chips, then a swipeable row of posters
 * per platform (shimmering placeholders while loading), then the TMDB/JustWatch credit.
 * [rows] null = loading.
 */
@Composable
internal fun TrendingSection(
    rows: List<TrendingRow>?,
    filter: TrendingFilter,
    onFilterChange: (TrendingFilter) -> Unit,
    onOpen: (TrendingTitle, StreamingService) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Trending now", style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
        GlassSegmented(
            options = TitleType.entries.map { it.label },
            selected = filter.type.ordinal,
            onSelect = { onFilterChange(filter.withType(TitleType.entries[it])) },
        )
        ChipRow {
            item(key = "all-genres") { FilterPill("All genres", filter.genre == null) { onFilterChange(filter.copy(genre = null)) } }
            items(TrendingFilters.genres(filter.type), key = { "g-" + it.key }) { g ->
                FilterPill(g.label, filter.genre == g.key) { onFilterChange(filter.copy(genre = if (filter.genre == g.key) null else g.key)) }
            }
        }
        ChipRow {
            item(key = "all-langs") { FilterPill("All languages", filter.language == null) { onFilterChange(filter.copy(language = null)) } }
            items(TrendingFilters.LANGUAGES, key = { "l-" + it.code }) { l ->
                FilterPill(l.label, filter.language == l.code) {
                    onFilterChange(filter.copy(language = if (filter.language == l.code) null else l.code))
                }
            }
        }

        when {
            rows == null -> repeat(2) { SkeletonRow() }
            rows.isEmpty() -> Text(
                "Nothing trending for that mix. Try another genre or language.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> for (row in rows) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel(row.service.displayName)
                    LazyRow(
                        modifier = Modifier.bleed(),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(row.titles, key = { it.type.wire + it.tmdbId }) { title -> PosterCard(title) { onOpen(title, row.service) } }
                    }
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

/** "Your watchlist": saved posters, newest first; tap one for the same sheet as a trending poster. */
@Composable
internal fun WatchlistRow(items: List<WatchlistItem>, onOpen: (TrendingTitle, StreamingService) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Your watchlist")
        LazyRow(
            modifier = Modifier.bleed(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.key }) { item -> PosterCard(item.title) { onOpen(item.title, item.service) } }
        }
    }
}

/** The apps among [rows] that [title] is trending on ("where can I watch this?"). */
internal fun servicesFor(title: TrendingTitle, rows: List<TrendingRow>?): List<StreamingService> =
    rows.orEmpty().filter { row -> row.titles.any { it.tmdbId == title.tmdbId && it.type == title.type } }.map { it.service }

/** Widens a horizontal row to the screen edges so items scroll under Home's 20 dp gutters. */
private fun Modifier.bleed(): Modifier = layout { measurable, constraints ->
    val gutter = 20.dp.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + gutter * 2))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
}

@Composable
private fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        modifier = Modifier.bleed(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Glass.palette
    Box(
        Modifier
            .clip(ChipShape)
            .then(if (selected) Modifier.background(p.textPrimary) else Modifier.raised(ChipShape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) p.base else p.textSecondary)
    }
}

/** Loading placeholder: a label and a few poster-shaped boxes with a light sweeping across. */
@Composable
private fun SkeletonRow() {
    val p = Glass.palette
    val reduceMotion = rememberReduceMotion()
    val sweep = if (reduceMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val value by transition.animateFloat(
            initialValue = -0.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
            label = "sweep",
        )
        value
    }
    val base = p.glassFallback
    val shine = p.textSecondary.copy(alpha = 0.18f)
    val brush = Brush.linearGradient(
        colors = listOf(base, shine, base),
        start = Offset(sweep * 900f - 300f, 0f),
        end = Offset(sweep * 900f + 300f, 600f),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { contentDescription = "Loading trending titles" }) {
        Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(8.dp)).background(brush))
        Row(Modifier.bleed().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(12.dp)).background(brush))
                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                    Box(Modifier.width(40.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
private fun PosterCard(title: TrendingTitle, onClick: () -> Unit) {
    val p = Glass.palette
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(title.title, title.year?.toString()).joinToString(", ") }
            .padding(bottom = 8.dp),
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
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text("%.1f".format(rating), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

/**
 * The tapped poster: watch it with friends (a room, then invites) or alone, save it for later, and
 * which other apps have it ([available], from the trending rows; tap one to switch to it).
 */
@Composable
internal fun TrendingSheetContent(
    title: TrendingTitle,
    service: StreamingService,
    busy: Boolean,
    onWithFriends: () -> Unit,
    onAlone: () -> Unit,
    saved: Boolean = false,
    onToggleSaved: () -> Unit = {},
    available: List<StreamingService> = emptyList(),
    onPickService: (StreamingService) -> Unit = {},
) {
    val p = Glass.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Poster(title, Modifier.width(84.dp).height(126.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        GlassButton(
            if (saved) "On your watchlist" else "Save to watchlist",
            onClick = onToggleSaved,
            icon = if (saved) Icons.Filled.Check else Icons.Filled.Add,
            style = GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val others = available.filter { it != service }
    if (others.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Also on", style = MaterialTheme.typography.labelLarge, color = p.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (other in others) FilterPill(other.displayName, false) { onPickService(other) }
            }
        }
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
