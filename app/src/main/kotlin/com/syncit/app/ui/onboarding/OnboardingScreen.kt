package com.syncit.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.syncit.voice.R as VoiceR

/** One page of the setup carousel. */
private enum class SetupPage { ACCESS, NOTIFICATIONS, MICROPHONE, BATTERY, DONE }

/**
 * Setup as a swipeable carousel (user's request, 2026-11-05): one page per permission, each asked
 * with Android's own prompt. Notifications and the microphone use the system permission dialogs;
 * Notification Access opens BingeJam's own switch (Android 11+); battery uses Android's "let the app
 * run in the background?" dialog. A page moves on by itself once its permission is granted, and
 * every page can be skipped. Also reopened from Settings → Setup & permissions.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessGranted by container.gateway.notificationAccessGranted.collectAsStateWithLifecycle()
    var notificationsAllowed by remember { mutableStateOf(Permissions.canPostNotifications(context)) }
    var micAllowed by remember { mutableStateOf(Permissions.canRecordAudio(context)) }
    var batteryExempt by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(context)) }
    // Once a system prompt was turned down, Android may not show it again: the button then opens settings.
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    var micAsked by rememberSaveable { mutableStateOf(false) }

    // Grants made in a system screen are only seen when we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        container.gateway.refresh()
        notificationsAllowed = Permissions.canPostNotifications(context)
        micAllowed = Permissions.canRecordAudio(context)
        batteryExempt = Permissions.isIgnoringBatteryOptimizations(context)
    }

    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        notificationsAsked = true
    }
    val micPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAllowed = granted
        micAsked = true
    }

    val pages = remember {
        listOfNotNull(
            SetupPage.ACCESS,
            SetupPage.NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
            SetupPage.MICROPHONE,
            SetupPage.BATTERY,
            SetupPage.DONE,
        )
    }
    val pager = rememberPagerState { pages.size }
    val granted: (SetupPage) -> Boolean = { page ->
        when (page) {
            SetupPage.ACCESS -> accessGranted
            SetupPage.NOTIFICATIONS -> notificationsAllowed
            SetupPage.MICROPHONE -> micAllowed
            SetupPage.BATTERY -> batteryExempt
            SetupPage.DONE -> true
        }
    }
    val next: () -> Unit = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pages.lastIndex)) } }

    // Granted while on its page: show the tick for a moment, then move on.
    val current = pages[pager.currentPage]
    val currentGranted = granted(current)
    LaunchedEffect(current, currentGranted) {
        if (current != SetupPage.DONE && currentGranted) {
            delay(AUTO_ADVANCE_MS)
            next()
        }
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Set up ${Brand.NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Glass.palette.textSecondary,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (current != SetupPage.DONE) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pages.lastIndex) } }) {
                        Text("Skip all", color = Glass.palette.textSecondary)
                    }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                when (val page = pages[index]) {
                    SetupPage.ACCESS -> PermissionPage(
                        icon = ImageVector.vectorResource(R.drawable.ic_stat_vyro),
                        title = "Keep everyone in sync",
                        body = stringResource(R.string.notification_access_disclosure, stringResource(R.string.app_name)),
                        hint = "Android opens ${Brand.NAME}'s switch. Turn it on, then come back.",
                        granted = accessGranted,
                        button = "Allow Notification Access",
                        onAllow = { Permissions.openNotificationAccessSettings(context) },
                        onSkip = next,
                        skipNote = "Without it only the manual 3, 2, 1 countdown works, and friends can't see your activity.",
                    )
                    SetupPage.NOTIFICATIONS -> PermissionPage(
                        icon = Icons.Filled.Notifications,
                        title = "Messages and invites",
                        body = "Get friends' messages, room invites and pings, and the quiet sync notification that keeps " +
                            "${Brand.NAME} running while you watch.",
                        hint = if (notificationsAsked && !notificationsAllowed) "Android won't ask again here. The button opens ${Brand.NAME}'s notification settings." else null,
                        granted = notificationsAllowed,
                        button = if (notificationsAsked && !notificationsAllowed) "Open notification settings" else "Allow notifications",
                        onAllow = {
                            if (notificationsAsked && !notificationsAllowed) Permissions.openAppNotificationSettings(context)
                            else notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onSkip = next,
                    )
                    SetupPage.MICROPHONE -> PermissionPage(
                        icon = ImageVector.vectorResource(VoiceR.drawable.ic_voice_mic),
                        title = "Talk while you watch",
                        body = "Voice chat in rooms uses your microphone, only while you're in voice. Nothing is recorded or stored.",
                        hint = if (micAsked && !micAllowed) "Android won't ask again here. The button opens app info → Permissions." else null,
                        granted = micAllowed,
                        button = if (micAsked && !micAllowed) "Open app permissions" else "Allow microphone",
                        onAllow = {
                            if (micAsked && !micAllowed) Permissions.openAppDetails(context)
                            else micPrompt.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSkip = next,
                        skipLabel = "Maybe later",
                    )
                    SetupPage.BATTERY -> PermissionPage(
                        icon = ImageVector.vectorResource(R.drawable.ic_setup_battery),
                        title = "Don't stop mid-movie",
                        body = "Some phones close background apps to save battery, which stops the sync while you're in " +
                            "Netflix. Let ${Brand.NAME} keep running; it barely uses battery when you're not in a room.",
                        hint = "Android asks you to confirm. Tap Allow.",
                        granted = batteryExempt,
                        button = "Allow in the background",
                        onAllow = { Permissions.openBatteryOptimizationSettings(context) },
                        onSkip = next,
                    )
                    SetupPage.DONE -> DonePage(
                        rows = pages.filter { it != SetupPage.DONE }.map { it to granted(it) },
                        onContinue = onDone,
                        onOpen = { target -> scope.launch { pager.animateScrollToPage(pages.indexOf(target)) } },
                    )
                }
            }

            PageDots(count = pages.size, current = pager.currentPage, done = pages.map(granted))
        }
    }
}

@Composable
private fun PermissionPage(
    icon: ImageVector,
    title: String,
    body: String,
    hint: String?,
    granted: Boolean,
    button: String,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String = "Not now",
    skipNote: String? = null,
) {
    val p = Glass.palette
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        PageIcon(icon, granted)
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = p.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = p.textSecondary, textAlign = TextAlign.Center)
        if (hint != null && !granted) {
            Spacer(Modifier.height(12.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = p.textTertiary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        if (granted) {
            GlassPanel(Modifier.fillMaxWidth(), spacing = 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = p.success)
                    Spacer(Modifier.width(12.dp))
                    Text("Allowed", style = MaterialTheme.typography.titleSmall, color = p.success)
                }
            }
        } else {
            GlassButton(button, onClick = onAllow, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onSkip, modifier = Modifier.padding(top = 4.dp)) {
                Text(skipLabel, color = p.textSecondary)
            }
            if (skipNote != null) {
                Text(skipNote, style = MaterialTheme.typography.bodySmall, color = p.textTertiary, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Big glowing icon; turns green with a tick once granted. */
@Composable
private fun PageIcon(icon: ImageVector, granted: Boolean) {
    val p = Glass.palette
    val a by animateColorAsState(if (granted) p.success else p.accent, label = "iconA")
    Box(
        Modifier
            .size(124.dp)
            .clip(CircleShape)
            .background(a),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (granted) Icons.Filled.Check else icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun DonePage(rows: List<Pair<SetupPage, Boolean>>, onContinue: () -> Unit, onOpen: (SetupPage) -> Unit) {
    val p = Glass.palette
    val accessOk = rows.firstOrNull { it.first == SetupPage.ACCESS }?.second != false
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        PageIcon(Icons.Filled.Check, granted = rows.all { it.second })
        Spacer(Modifier.height(24.dp))
        Text(
            if (rows.all { it.second }) "You're all set" else "Almost set",
            style = MaterialTheme.typography.headlineMedium,
            color = p.textPrimary,
        )
        Spacer(Modifier.height(16.dp))
        GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp) {
            for ((page, ok) in rows) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {
                        contentDescription = "${label(page)}: ${if (ok) "allowed" else "not allowed"}"
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (ok) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null, tint = if (ok) p.success else p.textTertiary)
                    Spacer(Modifier.width(12.dp))
                    Text(label(page), style = MaterialTheme.typography.bodyLarge, color = p.textPrimary, modifier = Modifier.weight(1f))
                    if (!ok) {
                        TextButton(onClick = { onOpen(page) }) { Text("Set up", color = p.accentText) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        GlassButton(
            text = "Start using ${Brand.NAME}",
            onClick = onContinue,
            style = if (accessOk) GlassButtonStyle.Primary else GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun label(page: SetupPage): String = when (page) {
    SetupPage.ACCESS -> "Notification Access (sync)"
    SetupPage.NOTIFICATIONS -> "Notifications"
    SetupPage.MICROPHONE -> "Microphone (voice chat)"
    SetupPage.BATTERY -> "Run in the background"
    SetupPage.DONE -> ""
}

/** Page dots: the current one is a wide pill; granted pages are green. */
@Composable
private fun PageDots(count: Int, current: Int, done: List<Boolean>) {
    val p = Glass.palette
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 24.dp else 8.dp, label = "dot")
            val color = when {
                i == current -> p.accent
                done.getOrElse(i) { false } && i != count - 1 -> p.success
                else -> p.textPrimary.copy(alpha = 0.25f)
            }
            Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(width).clip(PillShape).background(color))
        }
    }
}

private const val AUTO_ADVANCE_MS = 700L
