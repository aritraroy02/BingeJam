package com.syncit.app.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.UpdateChecker
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEnterRoom: (String) -> Unit,
    onJoinCode: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = Glass.palette
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refreshLastRoom() }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(actions = { GlassIconButton(Icons.Filled.Settings, "Settings", onOpenSettings) })
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Sync it",
                    style = MaterialTheme.typography.displaySmall.copy(
                        brush = Brush.horizontalGradient(listOf(p.accent, p.accent2)),
                    ),
                )
                Text(
                    "Hey ${viewModel.name.ifBlank { "there" }}. Watch Netflix or JioHotstar together, each on your own phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.textSecondary,
                )
                Spacer(Modifier.height(10.dp))

                update?.let { available ->
                    GlassPanel {
                        Text("Sync it ${available.version} is available", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        available.notes?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                            Text(it.trimStart('-', '*', ' '), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                        }
                        GlassButton(
                            text = "Download update",
                            icon = Icons.Filled.Refresh,
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, UpdateChecker.DOWNLOAD_URL.toUri()))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                GlassPanel {
                    Text("Start a watch party", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
                    Text(
                        "Create a room and invite friends. When anyone plays, pauses or skips, everyone follows.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                    GlassButton(
                        text = "Create a room",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { viewModel.create(onEnterRoom) },
                        loading = ui.busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }

                GlassPanel {
                    Text("Join a friend", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
                    GlassTextField(
                        value = ui.code,
                        onValueChange = viewModel::onCodeChange,
                        placeholder = "ROOM CODE",
                        textStyle = TextStyle(
                            fontFamily = Inter,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            letterSpacing = 6.sp,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { if (ui.codeValid) onJoinCode(ui.code) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassButton(
                        text = "Continue",
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        style = GlassButtonStyle.Secondary,
                        enabled = ui.codeValid,
                        onClick = { onJoinCode(ui.code) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ui.lastRoom?.let { last ->
                    Row(Modifier.fillMaxWidth()) {
                        GlassButton(
                            text = "Back to room $last",
                            icon = Icons.Filled.Refresh,
                            style = GlassButtonStyle.Secondary,
                            onClick = { onJoinCode(last) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                ui.error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
