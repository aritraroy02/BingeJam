package com.syncit.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.UserPrefs
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSwitchRow
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.social.ActivityAppIcon
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.MediaKind

private fun MediaKind.groupLabel(): String = when (this) {
    MediaKind.MOVIE_TV -> "Movies & TV"
    MediaKind.MUSIC -> "Music"
    MediaKind.VIDEO -> "Video"
}

private fun MediaKind.groupNote(): String = when (this) {
    MediaKind.MOVIE_TV -> "Friends see the title and can ask to join your watch party."
    MediaKind.MUSIC -> "Friends see the song and can ask to jam: the same track plays on their phone, in sync."
    MediaKind.VIDEO -> "Friends see the video title and can ask to jam: the same video plays on their phone, in sync."
}

/** Per-app toggles grouped by kind. Used by the setup page and Settings. */
@Composable
fun ActivityAppToggles(shared: Set<ActivityApp>, onToggle: (ActivityApp, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (kind in MediaKind.entries) {
            val apps = ActivityApp.entries.filter { it.kind == kind }
            if (apps.isEmpty()) continue
            SectionLabel(kind.groupLabel(), Modifier.padding(top = 6.dp))
            GlassPanel(Modifier.fillMaxWidth(), spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
                Text(kind.groupNote(), style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(4.dp))
                for (app in apps) {
                    GlassSwitchRow(
                        title = app.displayName,
                        checked = app in shared,
                        onCheckedChange = { onToggle(app, it) },
                        leading = { ActivityAppIcon(app) },
                    )
                }
            }
        }
    }
}

/**
 * First-run page (the user's explicit request): explains that friends can see what you play and
 * lets you pick apps. Everything starts off; nothing is shared until you opt in.
 */
@Composable
fun ActivitySetupScreen(prefs: UserPrefs, onDone: () -> Unit) {
    val p = Glass.palette
    val shared by prefs.sharedApps.collectAsStateWithLifecycle()

    GlassScreen {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 32.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Share what you're playing?", style = MaterialTheme.typography.headlineMedium, color = p.textPrimary)
            Text(
                "Your friends can see what you're watching or listening to, but only in the apps you turn on " +
                    "below. Strangers never see it.",
                style = MaterialTheme.typography.bodyLarge,
                color = p.textSecondary,
            )
            Text(
                "We use what the app shows in its media controls (title, artwork, playing or paused). " +
                    "We never read your notifications or messages. Change this any time in Settings, or go invisible.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )

            ActivityAppToggles(
                shared = shared,
                onToggle = { app, on -> prefs.setSharedApps(if (on) shared + app else shared - app) },
            )

            GlassButton(
                text = if (shared.isEmpty()) "Continue" else "Share ${shared.size} app${if (shared.size == 1) "" else "s"}",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            GlassButton(
                text = "Share nothing",
                style = GlassButtonStyle.Secondary,
                onClick = {
                    prefs.setSharedApps(emptySet())
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
