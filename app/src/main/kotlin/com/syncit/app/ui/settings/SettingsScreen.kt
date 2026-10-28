package com.syncit.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.BuildConfig
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.ui.activity.ActivityAppToggles
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassSwitchRow
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.ThemeMode
import com.syncit.core.config.Brand
import com.syncit.core.model.AuthState
import com.syncit.core.model.SignInProvider

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val p = Glass.palette
    val context = LocalContext.current
    val prefs = viewModel.prefs
    val mode by prefs.themeMode.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    val shared by prefs.sharedApps.collectAsStateWithLifecycle()
    val invisible by prefs.invisible.collectAsStateWithLifecycle()
    val sharingNow by viewModel.sharingNow.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = "Settings",
                navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel("Profile", Modifier.padding(top = 8.dp))
                GlassPanel(onClick = onEditProfile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile?.displayName ?: "?", size = 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@${profile?.username.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                            profile?.bio?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Icon(Icons.Filled.Edit, contentDescription = "Edit profile", tint = p.textTertiary)
                    }
                }

                SectionLabel("Activity sharing", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 6.dp, contentPadding = PaddingValues(14.dp)) {
                    GlassSwitchRow(
                        title = "Invisible",
                        subtitle = "Friends see you as offline and nothing you play is shared.",
                        checked = invisible,
                        onCheckedChange = prefs::setInvisible,
                    )
                    val now = sharingNow
                    Text(
                        when {
                            invisible -> "You're invisible."
                            shared.isEmpty() -> "You're not sharing any apps."
                            now != null -> "Friends see: ${viewModel.describe(now)}"
                            else -> "Nothing playing in a shared app right now."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                ActivityAppToggles(
                    shared = shared,
                    onToggle = { app, on -> prefs.setSharedApps(if (on) shared + app else shared - app) },
                )

                SectionLabel("Appearance", Modifier.padding(top = 12.dp))
                GlassPanel {
                    Text("Theme", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                    GlassSegmented(
                        options = listOf("System", "Light", "Dark"),
                        selected = mode.ordinal,
                        onSelect = { prefs.setThemeMode(ThemeMode.entries[it]) },
                    )
                    Text("System follows your phone's dark mode setting.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                }

                SectionLabel("App", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
                    GlassRow(
                        title = "Setup & permissions",
                        subtitle = "Notification Access, notifications, battery",
                        icon = Icons.Filled.Lock,
                        onClick = onOpenSetup,
                        trailing = { Chevron() },
                    )
                    GlassRow(
                        title = "Media diagnostics",
                        subtitle = "See what your streaming and music apps report",
                        icon = Icons.Filled.Build,
                        onClick = onOpenDiagnostics,
                        trailing = { Chevron() },
                    )
                }

                SectionLabel("Account", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
                    val signedIn = auth as? AuthState.SignedIn
                    GlassRow(
                        title = if (signingOut) "Signing out…" else "Sign out",
                        subtitle = signedIn?.let {
                            val via = when (it.provider) {
                                SignInProvider.GOOGLE -> "Google"
                                SignInProvider.EMAIL_LINK -> "email link"
                                SignInProvider.OTHER -> "another method"
                            }
                            listOfNotNull(it.email, "signed in with $via").joinToString(" · ")
                        },
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        onClick = { if (!signingOut) confirmSignOut = true },
                    )
                }

                SectionLabel("About", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
                    GlassRow(
                        title = "${Brand.NAME} ${BuildConfig.VERSION_NAME}",
                        subtitle = "${Brand.TAGLINE} Shared with your room: play, pause, position, and the title and " +
                            "artwork of what you're playing. Shared with friends: only the apps you turn on above. " +
                            "Never video, audio or notifications.",
                        icon = Icons.Filled.Info,
                    )
                    GlassRow(
                        title = "Licences",
                        subtitle = "Inter typeface, SIL Open Font License 1.1. Haze, Apache 2.0.",
                    )
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You'll leave any watch party or jam you're in. Your friends and chats stay with your account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut(beforeSignOut = { ChatBubbleService.stop(context) })
                }) { Text("Sign out", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Glass.palette.textTertiary)
}
