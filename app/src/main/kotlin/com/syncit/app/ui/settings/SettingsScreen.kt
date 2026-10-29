package com.syncit.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.BuildConfig
import com.syncit.app.data.LegalLinks
import com.syncit.app.data.UpdateChecker
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
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.ThemeMode
import com.syncit.core.config.Brand
import com.syncit.core.model.AuthState
import com.syncit.core.model.SignInProvider
import com.syncit.core.voice.VoiceDevice
import com.syncit.voice.R as VoiceR

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    deleteAccount: DeleteAccountViewModel,
    useFakeGoogle: Boolean,
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
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()
    val voiceDevices by viewModel.voiceDevices.collectAsStateWithLifecycle()
    var devicePicker by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    val openLegal: (String) -> Unit = { url ->
        if (!LegalLinks.open(context, url)) linkError = "No browser found. The page is at $url"
    }

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
                // Your profile, "Invisible" and what friends see now are on the Profile screen.
                SectionLabel("Activity sharing", Modifier.padding(top = 8.dp))
                Text(
                    if (invisible) {
                        "You're invisible, so nothing is shared. Turn it off on your profile."
                    } else {
                        "Friends see what you play in the apps you turn on here."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
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

                SectionLabel("Voice chat", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
                    GlassRow(
                        title = "Microphone",
                        subtitle = deviceLabel(voiceDevices.inputs, voiceDevices.selectedInput),
                        icon = ImageVector.vectorResource(VoiceR.drawable.ic_voice_mic),
                        onClick = { devicePicker = PICK_MIC },
                        trailing = { Chevron() },
                    )
                    GlassRow(
                        title = "Speaker",
                        subtitle = deviceLabel(voiceDevices.outputs, voiceDevices.selectedOutput),
                        icon = ImageVector.vectorResource(VoiceR.drawable.ic_voice_headset),
                        onClick = { devicePicker = PICK_SPEAKER },
                        trailing = { Chevron() },
                    )
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
                    GlassRow(
                        title = "Delete account",
                        subtitle = "Permanently delete your account, friends, messages and activity",
                        icon = Icons.Filled.Delete,
                        onClick = { if (!signingOut) deleteAccount.open() },
                        trailing = { Chevron() },
                    )
                }

                SectionLabel("About & legal", Modifier.padding(top = 12.dp))
                GlassPanel(spacing = 0.dp, contentPadding = PaddingValues(12.dp)) {
                    val check = updateCheck
                    val available = ((check as? SettingsViewModel.UpdateCheck.Done)?.result as? UpdateChecker.Result.Available)?.update
                    GlassRow(
                        title = "${Brand.NAME} ${BuildConfig.VERSION_NAME}",
                        subtitle = when (check) {
                            SettingsViewModel.UpdateCheck.Idle -> "Tap to check for updates"
                            SettingsViewModel.UpdateCheck.Checking -> "Checking for updates…"
                            is SettingsViewModel.UpdateCheck.Done -> when (check.result) {
                                UpdateChecker.Result.UpToDate -> "You're on the latest version"
                                is UpdateChecker.Result.Available -> "Version ${available?.version} is available. Tap to download."
                                UpdateChecker.Result.Failed -> "Couldn't check. Tap to try again."
                            }
                        },
                        icon = Icons.Filled.Info,
                        onClick = {
                            if (available != null) {
                                openLegal(UpdateChecker.DOWNLOAD_URL)
                            } else {
                                viewModel.checkForUpdate()
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
                            "what you're playing. Shared with friends: only the apps you turn on above. Never video, " +
                            "audio or notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
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
                        subtitle = "Inter typeface, SIL Open Font License 1.1. Haze, Apache 2.0.",
                    )
                    Text(
                        LegalLinks.NOT_AFFILIATED,
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
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
