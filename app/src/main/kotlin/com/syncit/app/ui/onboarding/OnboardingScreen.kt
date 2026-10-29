package com.syncit.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
import com.syncit.app.ui.glass.PillShape
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    val accessGranted by container.gateway.notificationAccessGranted.collectAsStateWithLifecycle()
    var batteryExempt by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(context)) }
    var notificationsAllowed by remember { mutableStateOf(Permissions.canPostNotifications(context)) }

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
                "Almost there",
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.horizontalGradient(listOf(p.accent, p.accent2)),
                ),
            )
            Text(
                "A few Android settings so ${Brand.NAME} can keep your watch parties in sync and show " +
                    "friends what you chose to share.",
                style = MaterialTheme.typography.bodyLarge,
                color = p.textSecondary,
            )

            // The notification step only exists on Android 13+, so number the steps that are shown.
            val askNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val steps = listOfNotNull(accessGranted, notificationsAllowed.takeIf { askNotifications }, batteryExempt)
            SetupProgress(done = steps.count { it }, total = steps.size)

            Step(number = 1, title = "Allow Notification Access", done = accessGranted) {
                StepText(stringResource(R.string.notification_access_disclosure, stringResource(R.string.app_name)))
                StepText("On the next screen, find ${Brand.NAME} in the list and turn it on, then come back.", small = true)
                StepButton(done = accessGranted, label = "Open settings") {
                    Permissions.openNotificationAccessSettings(context)
                }
            }

            if (askNotifications) {
                Step(number = 2, title = "Show the sync notification", done = notificationsAllowed) {
                    StepText(
                        "While you're in a room, ${Brand.NAME} shows one quiet notification so Android keeps " +
                            "it running while you watch. You can stop syncing from it.",
                    )
                    StepButton(done = notificationsAllowed, label = "Allow") {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            Step(number = steps.size, title = "Keep syncing in the background", done = batteryExempt) {
                StepText(
                    "Some phones stop background apps to save battery, which would stop the sync " +
                        "mid-movie. On the next screen, choose \"All apps\", find ${Brand.NAME}, and pick " +
                        "\"Don't optimize\" (or \"Unrestricted\").",
                )
                StepButton(done = batteryExempt, label = "Open battery settings") {
                    Permissions.openBatteryOptimizationSettings(context)
                }
            }

            // Skipping the key permission is possible but shouldn't look like the main path.
            GlassButton(
                text = if (accessGranted) "Continue" else "Continue without Notification Access",
                onClick = onDone,
                style = if (accessGranted) GlassButtonStyle.Primary else GlassButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (!accessGranted) {
                StepText(
                    "Without Notification Access only the manual 3, 2, 1 countdown works, and friends can't see your activity.",
                    small = true,
                )
            }
        }
    }
}

/** "2 of 3 done" with a bar that fills as each system setting is granted. */
@Composable
private fun SetupProgress(done: Int, total: Int) {
    val p = Glass.palette
    val fraction by animateFloatAsState(if (total == 0) 1f else done.toFloat() / total, label = "setup")
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
    ) {
        Text(
            if (done == total) "All set" else "$done of $total done",
            style = MaterialTheme.typography.labelMedium,
            color = if (done == total) p.success else p.textSecondary,
        )
        Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(p.textPrimary.copy(alpha = 0.12f))) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(PillShape)
                    .background(Brush.horizontalGradient(listOf(p.accent, p.accent2))),
            )
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
