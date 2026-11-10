package com.syncit.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.social.UsernameAvailability
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.profile.BirthDateField
import com.syncit.app.ui.theme.Glass

@Composable
fun UsernameScreen(viewModel: UsernameViewModel) {
    val p = Glass.palette
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val status by viewModel.availability.status.collectAsStateWithLifecycle()

    GlassScreen {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 32.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Pick a username", style = MaterialTheme.typography.headlineMedium, color = p.textPrimary)
            Text(
                "Friends add you by your username. You can change it later in Settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = p.textSecondary,
            )
            GlassPanel(Modifier.fillMaxWidth()) {
                SectionLabel("Username")
                UsernameField(
                    value = ui.username,
                    status = status,
                    onValueChange = viewModel::onUsernameChange,
                    suggestions = ui.suggestions,
                )
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                SectionLabel("Display name")
                GlassTextField(
                    value = ui.displayName,
                    onValueChange = viewModel::onDisplayNameChange,
                    placeholder = "Your name",
                    leadingIcon = Icons.Filled.Person,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Shown to friends and in watch parties.", style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                SectionLabel("Date of birth")
                BirthDateField(ui.birthDate, onPick = viewModel::onBirthDateChange)
                Text("Private. Friends and other people never see it.", style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
            }
            GlassButton(
                "Continue",
                onClick = viewModel::submit,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = status is UsernameAvailability.Status.Available && ui.displayName.isNotBlank() && ui.birthDate != null,
                loading = ui.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            ui.error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
