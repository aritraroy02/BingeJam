package com.syncit.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.R
import com.syncit.app.di.AppContainer
import com.syncit.app.permissions.Permissions
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.theme.Glass

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    val accessGranted by container.gateway.notificationAccessGranted.collectAsStateWithLifecycle()
    var batteryExempt by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(context)) }
    var notificationsAllowed by remember { mutableStateOf(Permissions.canPostNotifications(context)) }
    var name by rememberSaveable { mutableStateOf(container.prefs.displayName) }

    // Every grant happens in a system screen, so re-check whenever we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        container.gateway.refresh()
        batteryExempt = Permissions.isIgnoringBatteryOptimizations(context)
        notificationsAllowed = Permissions.canPostNotifications(context)
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsAllowed = granted }

    GlassScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Welcome to Sync it",
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.horizontalGradient(listOf(p.accent, p.accent2)),
                ),
            )
            Text(
                "Watch Netflix or JioHotstar together, each on your own phone. When anyone plays, " +
                    "pauses or skips, everyone's movie follows.",
                style = MaterialTheme.typography.bodyLarge,
                color = p.textSecondary,
            )

            Step(number = 1, title = "Allow Notification Access", done = accessGranted) {
                StepText(stringResource(R.string.notification_access_disclosure))
                StepText("On the next screen, turn on \"Sync it media control\", then come back.", small = true)
                StepButton(done = accessGranted, label = "Open settings") {
                    Permissions.openNotificationAccessSettings(context)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Step(number = 2, title = "Show the sync notification", done = notificationsAllowed) {
                    StepText(
                        "While you're in a room, Sync it shows one quiet notification so Android keeps " +
                            "it running while you watch. You can stop syncing from it.",
                    )
                    StepButton(done = notificationsAllowed, label = "Allow") {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            Step(number = 3, title = "Keep syncing in the background", done = batteryExempt) {
                StepText(
                    "Some phones stop background apps to save battery, which would stop the sync " +
                        "mid-movie. On the next screen, choose \"All apps\", find Sync it, and pick " +
                        "\"Don't optimize\" (or \"Unrestricted\").",
                )
                StepButton(done = batteryExempt, label = "Open battery settings") {
                    Permissions.openBatteryOptimizationSettings(context)
                }
            }

            Step(number = 4, title = "Your name", done = name.isNotBlank()) {
                GlassTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = "Shown to others in your room",
                    leadingIcon = Icons.Filled.Person,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            GlassButton(
                text = if (accessGranted) "Continue" else "Continue without Notification Access",
                onClick = {
                    container.prefs.displayName = name.trim()
                    onDone()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (!accessGranted) {
                StepText("Without Notification Access only the manual 3, 2, 1 countdown works.", small = true)
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    val p = Glass.palette
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (done) Brush.linearGradient(listOf(p.success, p.success))
                        else Brush.linearGradient(listOf(p.accent, p.accent2)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(18.dp))
                } else {
                    Text("$number", color = p.onAccent, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
        }
        content()
    }
}

@Composable
private fun StepText(text: String, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = Glass.palette.textSecondary,
    )
}

@Composable
private fun StepButton(done: Boolean, label: String, onClick: () -> Unit) {
    GlassButton(
        text = if (done) "Done · open again" else label,
        onClick = onClick,
        style = if (done) GlassButtonStyle.Secondary else GlassButtonStyle.Primary,
        modifier = Modifier.fillMaxWidth(),
    )
}
