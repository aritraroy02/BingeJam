package com.syncit.app.ui.account

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.auth.GoogleSignIn
import com.syncit.app.ui.common.BrandWordmark
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import kotlinx.coroutines.launch

/** Everything a signed-out user can see: welcome, email entry, "check your inbox", link confirm. */
@Composable
fun AccountFlow(viewModel: AuthViewModel, useFakeGoogle: Boolean) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = ui.step != AuthViewModel.Step.Welcome && ui.step != AuthViewModel.Step.Completing) {
        viewModel.back()
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (ui.step != AuthViewModel.Step.Welcome && ui.step != AuthViewModel.Step.Completing) {
                GlassTopBar(navigation = {
                    GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = { viewModel.back() })
                })
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val step = ui.step) {
                    AuthViewModel.Step.Welcome -> Welcome(
                        busy = ui.busy,
                        onGoogle = {
                            if (useFakeGoogle) {
                                viewModel.signInWithGoogleToken("fake-token")
                            } else {
                                val clientId = GoogleSignIn.serverClientId(context)
                                if (clientId == null) {
                                    viewModel.showError("Google sign-in isn't set up yet. Continue with email for now.")
                                } else {
                                    viewModel.onGoogleStarted()
                                    scope.launch { viewModel.onGoogleResult(GoogleSignIn.requestIdToken(context, clientId)) }
                                }
                            }
                        },
                        onEmail = viewModel::showEmail,
                    )

                    AuthViewModel.Step.EnterEmail -> EmailEntry(
                        title = "What's your email?",
                        body = "We'll email you a sign-in link. Open it on this phone and you're in, no password needed.",
                        email = ui.email,
                        valid = ui.emailValid,
                        busy = ui.busy,
                        button = "Send link",
                        onEmailChange = viewModel::onEmailChange,
                        onSubmit = viewModel::sendLink,
                    )

                    is AuthViewModel.Step.CheckInbox -> CheckInbox(
                        email = step.email,
                        resendIn = ui.resendIn,
                        busy = ui.busy,
                        onOpenMail = {
                            try {
                                context.startActivity(
                                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (_: ActivityNotFoundException) {
                                viewModel.showError("No email app found. Open your inbox and tap the link.")
                            }
                        },
                        onResend = viewModel::resend,
                        onChangeEmail = { viewModel.back() },
                    )

                    is AuthViewModel.Step.ConfirmEmail -> EmailEntry(
                        title = "Confirm your email",
                        body = "This link was requested on another phone, or before ${Brand.NAME} was reinstalled. " +
                            "Enter the email address it was sent to.",
                        email = ui.email,
                        valid = ui.emailValid,
                        busy = ui.busy,
                        button = "Sign in",
                        onEmailChange = viewModel::onEmailChange,
                        onSubmit = viewModel::confirmEmail,
                    )

                    AuthViewModel.Step.Completing -> Box(
                        Modifier.fillMaxWidth().padding(top = 160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(color = Glass.palette.accent)
                            Text("Signing you in…", style = MaterialTheme.typography.titleMedium, color = Glass.palette.textPrimary)
                        }
                    }
                }
                ui.error?.let {
                    Text(it, color = Glass.palette.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Welcome(busy: Boolean, onGoogle: () -> Unit, onEmail: () -> Unit) {
    val p = Glass.palette
    Spacer(Modifier.height(96.dp))
    BrandWordmark(style = MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 1.4f))
    Text(Brand.TAGLINE, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
    Text(
        "Watch Netflix, JioHotstar or Prime Video together, jam to Spotify, YouTube and more, each on your own phone, " +
            "and see what your friends are playing.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
    )
    Spacer(Modifier.height(48.dp))
    GlassButton("Continue with Google", onClick = onGoogle, loading = busy, modifier = Modifier.fillMaxWidth())
    GlassButton(
        "Continue with email",
        onClick = onEmail,
        icon = Icons.Filled.Email,
        style = GlassButtonStyle.Secondary,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Your email stays private. Friends find you by your username.",
        style = MaterialTheme.typography.bodySmall,
        color = p.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmailEntry(
    title: String,
    body: String,
    email: String,
    valid: Boolean,
    busy: Boolean,
    button: String,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val p = Glass.palette
    Text(title, style = MaterialTheme.typography.headlineMedium, color = p.textPrimary)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = p.textSecondary)
    GlassTextField(
        value = email,
        onValueChange = onEmailChange,
        placeholder = "you@example.com",
        leadingIcon = Icons.Filled.Email,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (valid) onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    GlassButton(
        button,
        onClick = onSubmit,
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        enabled = valid,
        loading = busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckInbox(
    email: String,
    resendIn: Int,
    busy: Boolean,
    onOpenMail: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
) {
    val p = Glass.palette
    Text("Check your inbox", style = MaterialTheme.typography.headlineMedium, color = p.textPrimary)
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("We sent a sign-in link to", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        Text(email, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
        Text(
            "Open the email on this phone and tap the link. It brings you straight back here. " +
                "Can't see it? Check spam, or resend.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
    }
    GlassButton("Open email app", onClick = onOpenMail, icon = Icons.Filled.Email, modifier = Modifier.fillMaxWidth())
    GlassButton(
        if (resendIn > 0) "Resend link in ${resendIn}s" else "Resend link",
        onClick = onResend,
        icon = Icons.Filled.Refresh,
        style = GlassButtonStyle.Secondary,
        enabled = resendIn == 0,
        loading = busy,
        modifier = Modifier.fillMaxWidth(),
    )
    GlassButton("Use a different email", onClick = onChangeEmail, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
}
