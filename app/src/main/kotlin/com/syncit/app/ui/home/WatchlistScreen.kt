package com.syncit.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.WatchlistItem
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.theme.Glass
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService

/**
 * The Watchlist tab (user's request, 2026-11-10): saved titles get their own tab, between Chats and
 * You, instead of a row on Watch. Tapping a poster opens the same sheet as on Watch.
 */
@Composable
fun WatchlistScreen(viewModel: HomeViewModel, onWatchWithFriends: (code: String) -> Unit) {
    val p = Glass.palette
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val trending by viewModel.trending.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf<Pair<TrendingTitle, StreamingService>?>(null) }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Watchlist")
            if (watchlist.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp).padding(bottom = LocalBottomBarInset.current), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(
                            "Tap a poster on Watch, then Save to watchlist. It'll wait here for movie night.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp + LocalBottomBarInset.current),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(watchlist, key = { it.key }) { item ->
                        PosterCard(item.title, fill = true) { pick = item.title to item.service }
                    }
                }
            }
        }
        NoticeBanner(ui.message, viewModel::dismissMessage, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = LocalBottomBarInset.current))

        val picked = pick
        GlassBottomSheet(visible = picked != null, onDismiss = { pick = null }) {
            if (picked != null) {
                val (title, service) = picked
                TrendingSheetContent(
                    title = title,
                    service = service,
                    busy = ui.busy,
                    saved = watchlist.any { it.key == WatchlistItem.keyOf(title) },
                    onToggleSaved = { viewModel.toggleWatchlist(title, service) },
                    available = servicesFor(title, trending),
                    onPickService = { other -> pick = title to other },
                    onWithFriends = {
                        viewModel.watchWithFriends(title, service) { code ->
                            pick = null
                            onWatchWithFriends(code)
                        }
                    },
                    onAlone = {
                        viewModel.watchAlone(title, service) { link ->
                            pick = null
                            if (!openTrending(context, service, link, title)) {
                                viewModel.showMessage("${service.displayName} isn't installed")
                            }
                        }
                    },
                )
            }
        }
    }
}
