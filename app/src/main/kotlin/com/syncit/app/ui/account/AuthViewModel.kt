package com.syncit.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.auth.GoogleSignIn
import com.syncit.app.di.AppContainer
import com.syncit.core.auth.EmailRequiredException
import com.syncit.core.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sign-in: Google, or a passwordless email link (send → check inbox → link opens the app). */
class AuthViewModel(container: AppContainer) : ViewModel() {
    private val auth = container.social.auth

    sealed interface Step {
        data object Welcome : Step
        data object EnterEmail : Step
        data class CheckInbox(val email: String) : Step

        /** The link was opened but we don't know which address it was sent to. */
        data class ConfirmEmail(val link: String) : Step
        data object Completing : Step
    }

    data class UiState(
        val step: Step = Step.Welcome,
        val email: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        /** Seconds until "Resend" is allowed again. */
        val resendIn: Int = 0,
    ) {
        val emailValid: Boolean get() = isValidEmail(email)
    }

    private val _ui = MutableStateFlow(UiState(email = auth.pendingEmail.orEmpty()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var cooldown: Job? = null

    init {
        // This ViewModel outlives a sign-in; start fresh for the next sign-out.
        viewModelScope.launch {
            auth.state.collect { state ->
                if (state is AuthState.SignedIn) {
                    cooldown?.cancel()
                    _ui.value = UiState()
                }
            }
        }
    }

    fun showEmail() = _ui.update { it.copy(step = Step.EnterEmail, error = null) }

    /** False when already at the first step (let the system handle back). */
    fun back(): Boolean {
        val step = _ui.value.step
        val previous = when (step) {
            Step.Welcome, Step.Completing -> return false
            Step.EnterEmail, is Step.ConfirmEmail -> Step.Welcome
            is Step.CheckInbox -> Step.EnterEmail
        }
        _ui.update { it.copy(step = previous, error = null, busy = false) }
        return true
    }

    fun onEmailChange(value: String) = _ui.update { it.copy(email = value.trim().take(254), error = null) }

    fun showError(message: String) = _ui.update { it.copy(error = message, busy = false) }

    fun onGoogleStarted() = _ui.update { it.copy(busy = true, error = null) }

    fun onGoogleResult(result: GoogleSignIn.Result) {
        when (result) {
            GoogleSignIn.Result.Cancelled -> _ui.update { it.copy(busy = false) }
            is GoogleSignIn.Result.Failed -> showError(result.message)
            is GoogleSignIn.Result.Token -> signInWithGoogleToken(result.idToken)
        }
    }

    /** The auth state change moves the app on; nothing to navigate here. */
    fun signInWithGoogleToken(idToken: String) = launchAction("Google sign-in didn't work") {
        auth.signInWithGoogleIdToken(idToken)
    }

    fun sendLink() {
        val email = _ui.value.email
        if (!isValidEmail(email)) return showError("That doesn't look like an email address")
        launchAction("Couldn't send the link") {
            auth.sendEmailSignInLink(email)
            _ui.update { it.copy(step = Step.CheckInbox(email)) }
            startCooldown()
        }
    }

    fun resend() {
        val step = _ui.value.step as? Step.CheckInbox ?: return
        if (_ui.value.resendIn > 0) return
        launchAction("Couldn't resend the link") {
            auth.sendEmailSignInLink(step.email)
            startCooldown()
        }
    }

    /** MainActivity got a sign-in link (already checked with AuthService.isEmailSignInLink). */
    fun onEmailLink(link: String) {
        val email = auth.pendingEmail
        if (email == null) {
            _ui.update { it.copy(step = Step.ConfirmEmail(link), error = null, busy = false) }
            return
        }
        complete(link, email)
    }

    fun confirmEmail() {
        val step = _ui.value.step as? Step.ConfirmEmail ?: return
        val email = _ui.value.email
        if (!isValidEmail(email)) return showError("That doesn't look like an email address")
        complete(step.link, email)
    }

    private fun complete(link: String, email: String?) {
        val back = _ui.value.step
        _ui.update { it.copy(step = Step.Completing, busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.completeEmailLinkSignIn(link, email)
                _ui.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: EmailRequiredException) {
                _ui.update { it.copy(step = Step.ConfirmEmail(link), busy = false) }
            } catch (e: Exception) {
                // Links expire and work once; send a fresh one.
                _ui.update {
                    it.copy(
                        step = if (back is Step.ConfirmEmail) back else Step.EnterEmail,
                        busy = false,
                        error = "That sign-in link didn't work (${e.message ?: "it may have expired"}). Send a new one.",
                    )
                }
            }
        }
    }

    private fun startCooldown() {
        cooldown?.cancel()
        cooldown = viewModelScope.launch {
            for (s in RESEND_COOLDOWN_S downTo 0) {
                _ui.update { it.copy(resendIn = s) }
                if (s > 0) delay(1_000)
            }
        }
    }

    private fun launchAction(failure: String, block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("$failure: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val RESEND_COOLDOWN_S = 30

        private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

        fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())
    }
}
