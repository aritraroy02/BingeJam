package com.syncit.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.BuildConfig
import com.syncit.app.R
import com.syncit.app.data.LegalLinks
import com.syncit.app.data.UpdateChecker
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.ui.activity.ActivityAppToggles
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.ThemeMode
import com.syncit.core.config.Brand
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.GroupInvitePolicy
import com.syncit.core.model.AuthState
import com.syncit.core.model.SignInProvider
import com.syncit.core.voice.VoiceDevice
import com.syncit.core.voice.VoiceDevices
import com.syncit.voice.R as VoiceR

/** The category pages inside Settings; MAIN lists them. */
private enum class SettingsPage(val title: String) {
    MAIN("Settings"),
    SHARING("Activity sharing"),
    AUDIO("Audio"),
    THEME("Theme"),
    PRIVACY("Privacy"),
    ACCOUNT("Account"),
    ABOUT("About & legal"),
}

/**
 * Settings as categories (user's request, 2026-11-03): Activity sharing, Audio, Theme, then Setup &
 * permissions, Media diagnostics, Account and About & legal. Each category opens its own page here;
 * back returns to the list.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    deleteAccount: DeleteAccountViewModel,
    useFakeGoogle: Boolean,
    /** Null when Settings is a tab (no back arrow on the list). */
    onBack: (() -> Unit)?,
    onEditProfile: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    /** Shown only to the owner and developers (App reports, 2026-11-10). */
    onOpenAppReports: (() -> Unit)? = null,
) {
    val p = Glass.palette
    val context = LocalContext.current
    val prefs = viewModel.prefs
    val mode by prefs.themeMode.collectAsStateWithLifecycle()
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    val shared by prefs.sharedApps.collectAsStateWithLifecycle()
    val invisible by prefs.invisible.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val voiceDevices by viewModel.voiceDevices.collectAsStateWithLifecycle()
    val invitePolicy by viewModel.invitePolicy.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    var devicePicker by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    val openLegal: (String) -> Unit = { url ->
        if (!LegalLinks.open(context, url)) linkError = "No browser found. The page is at $url"
    }

    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val back: (() -> Unit)? = if (page != SettingsPage.MAIN) ({ page = SettingsPage.MAIN }) else onBack
            GlassTopBar(
                title = page.title,
                navigation = back?.let { b -> { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", b) } },
            )
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState != SettingsPage.MAIN
                    (slideInHorizontally { w -> if (forward) w / 5 else -w / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> if (forward) -w / 5 else w / 5 } + fadeOut())
                },
                label = "settingsPage",
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                        .padding(top = 8.dp, bottom = 24.dp + LocalBottomBarInset.current),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (current) {
                        SettingsPage.MAIN -> MainPage(
                            sharingSummary = sharingSummary(shared, invisible),
                            audioSummary = "Mic: ${deviceLabel(voiceDevices.inputs, voiceDevices.selectedInput)} · " +
                                "Speaker: ${deviceLabel(voiceDevices.outputs, voiceDevices.selectedOutput)}",
                            themeSummary = THEME_OPTIONS[mode.ordinal],
                            accountSummary = (auth as? AuthState.SignedIn)?.email,
                            onOpen = { page = it },
                            onOpenSetup = onOpenSetup,
                            onOpenDiagnostics = onOpenDiagnostics,
                            onOpenAppReports = onOpenAppReports,
                        )
                        SettingsPage.SHARING -> SharingPage(
                            shared = shared,
                            invisible = invisible,
                            onToggle = { app, on -> prefs.setSharedApps(if (on) shared + app else shared - app) },
                        )
                        SettingsPage.AUDIO -> AudioPage(voiceDevices, onPick = { devicePicker = it })
                        SettingsPage.THEME -> ThemePage(mode, onSelect = { prefs.setThemeMode(it) })
                        SettingsPage.PRIVACY -> PrivacyPage(invitePolicy, onSelect = viewModel::setInvitePolicy)
                        SettingsPage.ACCOUNT -> AccountPage(
                            auth = auth,
                            signingOut = signingOut,
                            onSignOut = { if (!signingOut) confirmSignOut = true },
                            onDelete = { if (!signingOut) deleteAccount.open() },
                        )
                        SettingsPage.ABOUT -> AboutPage(viewModel, openLegal)
                    }
                }
            }
        }
        NoticeBanner(linkError, onDismiss = { linkError = null }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        DeleteAccountSheet(deleteAccount, useFakeGoogle)
    }

    devicePicker?.let { which ->
        val mic = which == PICK_MIC
        DevicePicker(
            title = if (mic) "Microphone" else "Speaker",
            automatic = if (mic) "Automatic (headset mic if connected, else the phone's)" else "Automatic (headset if connected, else loudspeaker)",
            devices = if (mic) voiceDevices.inputs else voiceDevices.outputs,
            selected = if (mic) voiceDevices.selectedInput else voiceDevices.selectedOutput,
            onPick = { id ->
                if (mic) viewModel.setVoiceInput(id) else viewModel.setVoiceOutput(id)
                devicePicker = null
            },
            onDismiss = { devicePicker = null },
        )
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

private const val PICK_MIC = "mic"
private const val PICK_SPEAKER = "speaker"

/** "Automatic", the picked device, or the pick with "(not connected)" while it's away. */
private fun deviceLabel(devices: List<VoiceDevice>, selected: String?): String = when {
    selected == null -> "Automatic"
    else -> devices.firstOrNull { it.id == selected }?.label ?: "${selected.substringAfter(':').ifEmpty { "Picked device" }} (not connected)"
}

@Composable
private fun DevicePicker(
    title: String,
    automatic: String,
    devices: List<VoiceDevice>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Glass.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                DeviceOption(automatic, selected == null) { onPick(null) }
                for (d in devices) DeviceOption(d.label, selected == d.id) { onPick(d.id) }
                Text(
                    "Used for room voice chat. Plug in or connect a headset to see it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DeviceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Glass.palette.textTertiary)
}

private val THEME_OPTIONS = listOf("System", "Light", "Dark")

/** "Netflix, Spotify on", "Off for all apps", or the invisible note. */
private fun sharingSummary(shared: Set<ActivityApp>, invisible: Boolean): String {
    if (invisible) return "You're invisible, so nothing is shared"
    val on = ActivityApp.entries.filter { it in shared }.map { it.displayName }
    return when {
        on.isEmpty() -> "Off for all apps"
        on.size <= 3 -> on.joinToString(", ") + " on"
        else -> on.take(2).joinToString(", ") + " and ${on.size - 2} more on"
    }
}

@Composable
private fun MainPage(
    sharingSummary: String,
    audioSummary: String,
    themeSummary: String,
    accountSummary: String?,
    onOpen: (SettingsPage) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAppReports: (() -> Unit)?,
) {
    GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
        CategoryRow("Activity sharing", sharingSummary, Icons.Filled.Share) { onOpen(SettingsPage.SHARING) }
        CategoryRow("Audio", audioSummary, ImageVector.vectorResource(VoiceR.drawable.ic_voice_headset)) { onOpen(SettingsPage.AUDIO) }
        CategoryRow("Theme", themeSummary, ImageVector.vectorResource(R.drawable.ic_settings_theme)) { onOpen(SettingsPage.THEME) }
    }
    GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
        CategoryRow("Setup & permissions", "Notification Access, notifications, battery", Icons.Filled.Notifications, onOpenSetup)
        CategoryRow("Media diagnostics", "See what your streaming and music apps report", Icons.Filled.Build, onOpenDiagnostics)
        onOpenAppReports?.let { CategoryRow("App reports", "Crashes, users and notifications · locked", Icons.Filled.Warning, it) }
    }
    GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
        CategoryRow("Privacy", "Group invites, private username", Icons.Filled.Lock) { onOpen(SettingsPage.PRIVACY) }
        CategoryRow("Account", accountSummary ?: "Sign out, delete account", Icons.Filled.AccountCircle) { onOpen(SettingsPage.ACCOUNT) }
        CategoryRow("About & legal", "${Brand.NAME} ${BuildConfig.VERSION_NAME} · updates, terms, privacy", Icons.Filled.Info) {
            onOpen(SettingsPage.ABOUT)
        }
    }
}

@Composable
private fun CategoryRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    GlassRow(title = title, subtitle = subtitle, icon = icon, onClick = onClick, trailing = { Chevron() })
}

/** One switch per app (Netflix, JioHotstar, Prime Video, Spotify…), grouped by kind. */
@Composable
private fun SharingPage(shared: Set<ActivityApp>, invisible: Boolean, onToggle: (ActivityApp, Boolean) -> Unit) {
    // Your profile, "Invisible" and what friends see now are on the Profile screen.
    Text(
        if (invisible) {
            "You're invisible, so nothing is shared. Turn it off on your profile."
        } else {
            "Friends see what you play in the apps you turn on here. Everything else stays private."
        },
        style = MaterialTheme.typography.bodySmall,
        color = Glass.palette.textSecondary,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    ActivityAppToggles(shared = shared, onToggle = onToggle)
}

@Composable
private fun AudioPage(voiceDevices: VoiceDevices, onPick: (String) -> Unit) {
    SectionLabel("Voice chat in rooms")
    GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
        GlassRow(
            title = "Microphone",
            subtitle = deviceLabel(voiceDevices.inputs, voiceDevices.selectedInput),
            icon = ImageVector.vectorResource(VoiceR.drawable.ic_voice_mic),
            onClick = { onPick(PICK_MIC) },
            trailing = { Chevron() },
        )
        GlassRow(
            title = "Speaker",
            subtitle = deviceLabel(voiceDevices.outputs, voiceDevices.selectedOutput),
            icon = ImageVector.vectorResource(VoiceR.drawable.ic_voice_headset),
            onClick = { onPick(PICK_SPEAKER) },
            trailing = { Chevron() },
        )
    }
    Text(
        "Automatic uses a headset when one is connected. Volume, HD / SD and mute are in the room's voice controls.",
        style = MaterialTheme.typography.bodySmall,
        color = Glass.palette.textSecondary,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Group invites: ask first or join directly (user's request, 2026-11-05); usernames are private. */
@Composable
private fun PrivacyPage(policy: GroupInvitePolicy, onSelect: (GroupInvitePolicy) -> Unit) {
    val p = Glass.palette
    GlassPanel {
        Text("Group invites", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
        GlassSegmented(
            options = listOf("Ask me first", "Join directly"),
            selected = if (policy == GroupInvitePolicy.DIRECT) 1 else 0,
            onSelect = { onSelect(if (it == 1) GroupInvitePolicy.DIRECT else GroupInvitePolicy.ASK) },
        )
        Text(
            if (policy == GroupInvitePolicy.DIRECT) {
                "Friends can add you to a group straight away. You can leave any group whenever you like."
            } else {
                "When a friend adds you to a group, you get an invite at the top of Chats to join or decline."
            },
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
    }
    GlassPanel {
        Text("Your username is private", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
        Text(
            "Nobody can browse usernames. People find you only by typing your full username exactly, so share it with friends yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
    }
}

@Composable
private fun ThemePage(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val p = Glass.palette
    GlassPanel {
        Text("App theme", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
        GlassSegmented(
            options = THEME_OPTIONS,
            selected = mode.ordinal,
            onSelect = { onSelect(ThemeMode.entries[it]) },
        )
        Text("System follows your phone's dark mode setting.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
    }
}

@Composable
private fun AccountPage(auth: AuthState, signingOut: Boolean, onSignOut: () -> Unit, onDelete: () -> Unit) {
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
            onClick = onSignOut,
        )
        GlassRow(
            title = "Delete account",
            subtitle = "Permanently delete your account, friends, messages and activity",
            icon = Icons.Filled.Delete,
            onClick = onDelete,
            trailing = { Chevron() },
        )
    }
}

@Composable
private fun AboutPage(viewModel: SettingsViewModel, openLegal: (String) -> Unit) {
    val p = Glass.palette
    val check by viewModel.updateCheck.collectAsStateWithLifecycle()
    GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
        val available = ((check as? SettingsViewModel.UpdateCheck.Done)?.result as? UpdateChecker.Result.Available)?.update
        GlassRow(
            title = "${Brand.NAME} ${BuildConfig.VERSION_NAME}",
            subtitle = when {
                !UpdateChecker.enabled -> "Updates come from the Indus Appstore. Tap to open it."
                check == SettingsViewModel.UpdateCheck.Idle -> "Tap to check for updates"
                check == SettingsViewModel.UpdateCheck.Checking -> "Checking for updates…"
                else -> when ((check as SettingsViewModel.UpdateCheck.Done).result) {
                    UpdateChecker.Result.UpToDate -> "You're on the latest version"
                    is UpdateChecker.Result.Available -> "Version ${available?.version} is available. Tap to download."
                    UpdateChecker.Result.Failed -> "Couldn't check. Tap to try again."
                }
            },
            icon = Icons.Filled.Info,
            onClick = if (!UpdateChecker.enabled) ({ openLegal(UpdateChecker.INDUS_URL) }) else {
                {
                    if (available != null) {
                        openLegal(UpdateChecker.DOWNLOAD_URL)
                    } else {
                        viewModel.checkForUpdate()
                    }
                }
            },
            trailing = {
                if (check == SettingsViewModel.UpdateCheck.Checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
                }
            },
        )
        Text(
            "${Brand.TAGLINE} Shared with your room: play, pause, position, and the title and artwork of " +
                "what you're playing. Shared with friends: only the apps you turn on in Activity sharing. Never " +
                "video, audio or notifications.",
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        GlassRow(
            title = "Terms of Service",
            icon = Icons.AutoMirrored.Filled.List,
            onClick = { openLegal(LegalLinks.TERMS) },
            trailing = { Chevron() },
        )
        GlassRow(
            title = "Privacy Policy",
            icon = Icons.Filled.Lock,
            onClick = { openLegal(LegalLinks.PRIVACY) },
            trailing = { Chevron() },
        )
        GlassRow(
            title = "Deleting your account",
            subtitle = "What gets deleted, and how to ask without the app",
            icon = Icons.Filled.Delete,
            onClick = { openLegal(LegalLinks.DELETE_ACCOUNT_HELP) },
            trailing = { Chevron() },
        )
        GlassRow(
            title = "Licences",
            subtitle = "Inter typeface, SIL Open Font License 1.1. Haze, Apache 2.0. Trending titles from TMDB: " +
                "this product uses the TMDB API but is not endorsed or certified by TMDB. Where to watch by JustWatch.",
        )
        Text(
            LegalLinks.NOT_AFFILIATED,
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
