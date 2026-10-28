package com.syncit.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.auth.GoogleSignIn
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.settings.DeleteAccountViewModel.Step
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import com.syncit.core.model.SignInProvider
import kotlinx.coroutines.launch

/** Must sit last inside a GlassScreen so it draws over the screen and blurs its backdrop. */
@Composable
fun DeleteAccountSheet(viewModel: DeleteAccountViewModel, useFakeGoogle: Boolean) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val visible = ui.step != Step.Closed && ui.step != Step.Deleted
    GlassBottomSheet(visible = visible, onDismiss = viewModel::close) {
        when (val step = ui.step) {
            Step.Confirm -> Confirm(ui, viewModel)
            Step.Deleting -> Busy("Deleting your account…", "This takes a few seconds. Keep the app open.")
            Step.SigningIn -> Busy("Signing you in…", null)
            Step.NeedsSignIn -> NeedsSignIn(ui, viewModel, useFakeGoogle)
            is Step.CheckInbox -> CheckInbox(step.email, viewModel)
            Step.Closed, Step.Deleted -> Unit
        }
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Glass.palette.danger) }
    }
}

@Composable
private fun ColumnScope.Confirm(ui: DeleteAccountViewModel.UiState, viewModel: DeleteAccountViewModel) {
    val p = Glass.palette
    Text("Delete your account?", style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
    if (ui.signedInAgain) {
        Text("You're signed in again. Tap Delete account to finish.", style = MaterialTheme.typography.bodyMedium, color = p.accent)
    }
    Text(
        "This permanently deletes your ${Brand.NAME} account and everything in it:",
        style = MaterialTheme.typography.bodyMedium,
        color = p.textSecondary,
    )
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            "your profile and username" + (ui.username?.let { " @$it" } ?: ""),
            "your friends and friend requests",
            "your messages",
            "your activity and online status",
            "your profile picture",
        ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = p.textPrimary) }
    }
    Text(
        "You'll leave any watch party or jam you're in. This can't be undone, and the username may be taken by someone else.",
        style = MaterialTheme.typography.bodyMedium,
        color = p.textSecondary,
    )
    val prompt = ui.username?.let { "Type $it or ${DeleteAccountViewModel.CONFIRM_WORD} to confirm" }
        ?: "Type ${DeleteAccountViewModel.CONFIRM_WORD} to confirm"
    Text(prompt, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
    GlassTextField(
        value = ui.typed,
        onValueChange = viewModel::onTypedChange,
        placeholder = ui.username ?: DeleteAccountViewModel.CONFIRM_WORD,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (ui.confirmed) viewModel.delete() }),
        modifier = Modifier.fillMaxWidth(),
    )
    GlassButton(
        "Delete account",
        onClick = viewModel::delete,
        icon = Icons.Filled.Delete,
        style = GlassButtonStyle.Danger,
        enabled = ui.confirmed,
        modifier = Modifier.fillMaxWidth(),
    )
    GlassButton("Cancel", onClick = viewModel::close, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ColumnScope.NeedsSignIn(ui: DeleteAccountViewModel.UiState, viewModel: DeleteAccountViewModel, useFakeGoogle: Boolean) {
    val p = Glass.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text("Sign in again to confirm", style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
    Text(
        "To protect your account, deleting it needs a recent sign-in. Sign in again with " +
            (ui.email ?: "the same account") + ", then tap Delete account. Nothing has been deleted yet.",
        style = MaterialTheme.typography.bodyMedium,
        color = p.textSecondary,
    )
    val google = ui.provider == SignInProvider.GOOGLE || ui.provider == SignInProvider.OTHER || ui.email == null
    val email = ui.email != null && ui.provider != SignInProvider.GOOGLE
    if (google) {
        GlassButton(
            "Sign in again with Google",
            onClick = {
                if (useFakeGoogle) {
                    viewModel.onGoogleResult(GoogleSignIn.Result.Token("fake-token", ui.email))
                } else {
                    val clientId = GoogleSignIn.serverClientId(context)
                    if (clientId == null) {
                        viewModel.onGoogleResult(GoogleSignIn.Result.Failed("Google sign-in isn't set up in this build."))
                    } else {
                        viewModel.onGoogleStarted()
                        scope.launch { viewModel.onGoogleResult(GoogleSignIn.requestIdToken(context, clientId)) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (email) {
        GlassButton(
            "Email me a sign-in link",
            onClick = viewModel::sendSignInLink,
            icon = Icons.Filled.Email,
            style = if (google) GlassButtonStyle.Secondary else GlassButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    GlassButton("Cancel", onClick = viewModel::close, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ColumnScope.CheckInbox(email: String, viewModel: DeleteAccountViewModel) {
    val p = Glass.palette
    val context = LocalContext.current
    Text("Check your inbox", style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
    Text(
        "We sent a sign-in link to $email. Open it on this phone and you'll come back here to finish deleting your account.",
        style = MaterialTheme.typography.bodyMedium,
        color = p.textSecondary,
    )
    GlassButton(
        "Open email app",
        onClick = {
            try {
                context.startActivity(
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                // No email app: the text above already says where the link is.
            }
        },
        icon = Icons.Filled.Email,
        modifier = Modifier.fillMaxWidth(),
    )
    GlassButton("Send a new link", onClick = viewModel::sendSignInLink, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
    GlassButton("Cancel", onClick = viewModel::close, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Busy(title: String, body: String?) {
    val p = Glass.palette
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = p.accent)
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
    }
}
