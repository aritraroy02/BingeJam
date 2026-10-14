package com.syncit.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.social.UsernameAvailability
import com.syncit.app.ui.account.UsernameField
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass

@Composable
fun EditProfileScreen(viewModel: EditProfileViewModel, onBack: () -> Unit) {
    val p = Glass.palette
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val status by viewModel.availability.status.collectAsStateWithLifecycle()
    val usernameOk = status is UsernameAvailability.Status.Available || status == UsernameAvailability.Status.Unchanged

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = "Edit profile",
                navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
            )
            if (!ui.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                return@Column
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassPanel(Modifier.fillMaxWidth()) {
                    SectionLabel("Username")
                    UsernameField(value = ui.username, status = status, onValueChange = viewModel::onUsernameChange)
                    Text(
                        "Changing it frees your old username for someone else.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
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
                }
                GlassPanel(Modifier.fillMaxWidth()) {
                    SectionLabel("Bio")
                    GlassTextField(
                        value = ui.bio,
                        onValueChange = viewModel::onBioChange,
                        placeholder = "A line about you (optional)",
                        singleLine = false,
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${ui.bio.length}/${EditProfileViewModel.MAX_BIO}",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
                GlassButton(
                    "Save",
                    onClick = { viewModel.save(onSaved = onBack) },
                    enabled = usernameOk && ui.displayName.isNotBlank(),
                    loading = ui.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
