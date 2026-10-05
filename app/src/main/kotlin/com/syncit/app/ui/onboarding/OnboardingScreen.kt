package com.syncit.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.R
import com.syncit.app.di.AppContainer
import com.syncit.app.permissions.Permissions
import com.syncit.app.ui.theme.StatusColors

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Welcome to Sync it", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Watch Netflix or JioHotstar together, each on your own phone. When anyone plays, " +
                    "pauses or skips, everyone's movie follows.",
            )

            Step(number = 1, title = "Allow Notification Access", done = accessGranted) {
                Text(stringResource(R.string.notification_access_disclosure))
                Text(
                    "On the next screen, turn on \"Sync it media control\", then come back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                StepButton(done = accessGranted, label = "Open settings") {
                    Permissions.openNotificationAccessSettings(context)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Step(number = 2, title = "Show the sync notification", done = notificationsAllowed) {
                    Text(
                        "While you're in a room, Sync it shows one quiet notification so Android keeps " +
                            "it running while you watch. You can stop syncing from it.",
                    )
                    StepButton(done = notificationsAllowed, label = "Allow") {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            Step(number = 3, title = "Keep syncing in the background", done = batteryExempt) {
                Text(
                    "Some phones stop background apps to save battery, which would stop the sync " +
                        "mid-movie. On the next screen, choose \"All apps\", find Sync it, and pick " +
                        "\"Don't optimize\" (or \"Unrestricted\").",
                )
                StepButton(done = batteryExempt, label = "Open battery settings") {
                    Permissions.openBatteryOptimizationSettings(context)
                }
            }

            Step(number = 4, title = "Your name", done = name.isNotBlank()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Shown to others in your room") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    container.prefs.displayName = name.trim()
                    onDone()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (accessGranted) "Continue" else "Continue without Notification Access")
            }
            if (!accessGranted) {
                Text(
                    "Without Notification Access only the manual 3, 2, 1 countdown works.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (done) "✓  $title" else "$number.  $title",
                fontWeight = FontWeight.Bold,
                color = if (done) StatusColors.Online else MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun StepButton(done: Boolean, label: String, onClick: () -> Unit) {
    if (done) {
        OutlinedButton(onClick = onClick) { Text("Done ✓  (open again)") }
    } else {
        Button(onClick = onClick) { Text(label) }
    }
}
