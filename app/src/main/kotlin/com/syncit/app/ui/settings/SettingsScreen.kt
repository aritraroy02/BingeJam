package com.syncit.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.BuildConfig
import com.syncit.app.data.UserPrefs
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    prefs: UserPrefs,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val p = Glass.palette
    val mode by prefs.themeMode.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(prefs.displayName) }

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
                SectionLabel("Appearance", Modifier.padding(top = 8.dp))
                GlassPanel {
                    Text("Theme", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                    GlassSegmented(
                        options = listOf("System", "Light", "Dark"),
                        selected = mode.ordinal,
                        onSelect = { prefs.setThemeMode(ThemeMode.entries[it]) },
                    )
                    Text(
                        "System follows your phone's dark mode setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                    )
                }

                SectionLabel("Profile", Modifier.padding(top = 12.dp))
                GlassPanel {
                    Text("Your name", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                    GlassTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(40)
                            if (name.isNotBlank()) prefs.displayName = name.trim()
                        },
                        placeholder = "Shown to others in your room",
                        leadingIcon = Icons.Filled.Person,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SectionLabel("App", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    GlassRow(
                        title = "Setup & permissions",
                        subtitle = "Notification Access, notifications, battery",
                        icon = Icons.Filled.Lock,
                        onClick = onOpenSetup,
                        trailing = { Chevron() },
                    )
                    GlassRow(
                        title = "Media diagnostics",
                        subtitle = "See what Netflix and JioHotstar report",
                        icon = Icons.Filled.Build,
                        onClick = onOpenDiagnostics,
                        trailing = { Chevron() },
                    )
                }

                SectionLabel("About", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    GlassRow(
                        title = "Sync it ${BuildConfig.VERSION_NAME}",
                        subtitle = "Shared with your room: play, pause, position, and the title and artwork of what you're watching. Never video, audio or notifications.",
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
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = Glass.palette.textTertiary,
    )
}
