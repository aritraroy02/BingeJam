package com.syncit.app.ui.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.ui.common.ArtworkCard
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.PlatformBadge
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter

@Composable
fun JoinPreviewScreen(viewModel: JoinPreviewViewModel, onJoined: (String) -> Unit, onBack: () -> Unit) {
    val p = Glass.palette
    val state by viewModel.preview.collectAsStateWithLifecycle()
    val join by viewModel.join.collectAsStateWithLifecycle()
    val preview = (state as? JoinPreviewViewModel.PreviewState.Loaded)?.preview
    val artwork = rememberArtwork(preview?.artworkJpeg)
    val jam = preview?.service?.isJam == true
    LaunchedEffect(join.joined) { if (join.joined) onJoined(viewModel.code) }

    GlassScreen(artwork = artwork) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(navigation = {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = {
                    viewModel.dismiss()
                    onBack()
                })
            })
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(if (jam) "You're invited to a jam" else "You're invited to a watch party", style = MaterialTheme.typography.labelLarge, color = p.textSecondary)

                if (state is JoinPreviewViewModel.PreviewState.Loading) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = p.accent)
                    }
                } else if (artwork != null) {
                    ArtworkCard(artwork, Modifier.fillMaxWidth(if (artwork.width >= artwork.height) 1f else 0.62f))
                }

                if (preview?.service != null) PlatformBadge(preview.service!!)

                Text(
                    preview?.title ?: "Room ${viewModel.code}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = p.textPrimary,
                    textAlign = TextAlign.Center,
                )
                preview?.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = p.textSecondary, textAlign = TextAlign.Center)
                }

                GlassPanel(Modifier.fillMaxWidth(), spacing = 14.dp) {
                    if (preview != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(preview.hostName, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Hosted by ${preview.hostName}", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                                Text(
                                    when {
                                        preview.title == null -> if (jam) "Hasn't started anything yet" else "Hasn't started a movie yet"
                                        jam -> "Their track starts on your phone and stays in sync"
                                        else -> "Everyone's playback stays in sync"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = p.textSecondary,
                                )
                            }
                        }
                    } else if (state is JoinPreviewViewModel.PreviewState.Loaded) {
                        Text(
                            "Couldn't load what's playing. You can still join with the code.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Room code", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.weight(1f))
                        Text(
                            viewModel.code,
                            fontFamily = Inter,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = 3.sp,
                            color = p.textPrimary,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                GlassButton(
                    text = if (jam) "Join the jam" else "Join watch party",
                    icon = Icons.Filled.PlayArrow,
                    loading = join.busy,
                    onClick = viewModel::join,
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassButton(
                    text = "Not now",
                    style = GlassButtonStyle.Secondary,
                    onClick = {
                        viewModel.dismiss()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                join.error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            }
        }
    }
}
