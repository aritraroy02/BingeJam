package com.syncit.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.syncit.app.auth.GoogleSignIn
import com.syncit.app.di.AppContainer
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.push.PushNotifications
import com.syncit.core.auth.AuthService
import com.syncit.core.auth.RecentLoginRequiredException
import com.syncit.core.model.AuthState
import com.syncit.core.model.SignInProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What has to happen on this phone around [AuthService.deleteAccount]. */
interface AccountDeletionHooks {
    /**
     * Stop syncing and the chat bubble and pause presence/activity. The room itself is NOT left:
     * deleteAccount's eraser reads the transport's active room to remove our member entry and,
     * if we host it, the room preview (which holds our display name).
     */
    suspend fun beforeDelete()

    /** The account [uid] is gone (and we're signed out): forget what this phone stored for it. */
    suspend fun afterDelete(uid: String)

    /**
     * Deletion was cancelled or failed: the account still exists. Leave the room whose sync
     * [beforeDelete] stopped (as before) and let presence/activity run again.
     */
    suspend fun abandon()
}

/**
 * Settings → Account → Delete account.
 *
 * Confirm (type the username or DELETE) → Deleting → Deleted (the app falls back to the signed-out
 * start screen on its own, because deleteAccount signs out). If Firebase wants a fresh sign-in
 * ([RecentLoginRequiredException]): NeedsSignIn → Google, or an email link (CheckInbox, then the
 * link comes back through MainActivity → [onEmailLink]) → back to Confirm to tap Delete again.
 *
 * Activity-scoped like every ViewModel here (no nav library), so it survives a trip to the email
 * app and the Settings screen being briefly replaced by the splash.
 */
class DeleteAccountViewModel(
    private val auth: AuthService,
    private val myUsername: Flow<String?>,
    private val hooks: AccountDeletionHooks,
    private val scope: CoroutineScope,
) : ViewModel(scope) {

    sealed interface Step {
        data object Closed : Step
        data object Confirm : Step
        data object Deleting : Step

        /** Firebase refused: the sign-in is too old. */
        data object NeedsSignIn : Step

        /** Google sheet open, a link being sent, or a link being completed. */
        data object SigningIn : Step
        data class CheckInbox(val email: String) : Step

        /** Done; shown as a note on the signed-out start screen until someone signs in. */
        data object Deleted : Step
    }

    data class UiState(
        val step: Step = Step.Closed,
        val typed: String = "",
        val username: String? = null,
        val provider: SignInProvider? = null,
        /** The signed-in account's email: where a re-auth link goes, and which Google account to pick. */
        val email: String? = null,
        /** Just signed in again: the sheet says "tap Delete account to finish". */
        val signedInAgain: Boolean = false,
        val error: String? = null,
    ) {
        val confirmed: Boolean get() = matchesConfirmation(typed, username)

        /** Back / scrim taps close the sheet except while something is in flight. */
        val dismissable: Boolean get() = step != Step.Deleting && step != Step.SigningIn
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** The account this flow is deleting; a re-auth must land on the same one. */
    private var targetUid: String? = null

    /** [AccountDeletionHooks.beforeDelete] ran and nothing has undone it yet. */
    private var prepared = false

    init {
        scope.launch {
            auth.state.collect { state ->
                val step = _ui.value.step
                when {
                    // Next account signed in: forget the "account deleted" note.
                    state is AuthState.SignedIn && step == Step.Deleted -> _ui.value = UiState()
                    // Signed out some other way mid-flow (deleteAccount itself signs out while Deleting).
                    state is AuthState.SignedOut && step != Step.Closed && step != Step.Deleting && step != Step.Deleted -> {
                        undoPrepare()
                        _ui.value = UiState()
                    }
                }
            }
        }
    }

    fun open() {
        val me = auth.state.value as? AuthState.SignedIn ?: return
        if (_ui.value.step != Step.Closed) return
        targetUid = me.uid
        _ui.value = UiState(step = Step.Confirm, provider = me.provider, email = me.email)
        scope.launch {
            val name = try {
                myUsername.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _ui.update { it.copy(username = name) }
        }
    }

    /** Cancel / back / scrim, or dismissing the "account deleted" note. Ignored while busy. */
    fun close() {
        if (!_ui.value.dismissable || _ui.value.step == Step.Closed) return
        undoPrepare()
        targetUid = null
        _ui.value = UiState()
    }

    fun onTypedChange(value: String) = _ui.update { it.copy(typed = value.take(64), error = null) }

    fun delete() {
        val state = _ui.value
        if (state.step != Step.Confirm || !state.confirmed) return
        val uid = auth.currentUid
        if (uid == null || uid != targetUid) {
            _ui.update { it.copy(error = "You're signed in to a different account now, so nothing was deleted.") }
            return
        }
        _ui.update { it.copy(step = Step.Deleting, error = null) }
        scope.launch {
            try {
                if (!prepared) {
                    hooks.beforeDelete()
                    prepared = true
                }
                auth.deleteAccount()
                prepared = false
                targetUid = null
                hooks.afterDelete(uid)
                _ui.value = UiState(step = Step.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RecentLoginRequiredException) {
                // Stay prepared (out of the room, presence paused): they'll most likely go on.
                _ui.update { it.copy(step = Step.NeedsSignIn, signedInAgain = false) }
            } catch (e: Exception) {
                undoPrepare()
                _ui.update {
                    it.copy(
                        step = Step.Confirm,
                        error = "Couldn't delete your account: ${e.message ?: e.javaClass.simpleName}. Check your connection and try again.",
                    )
                }
            }
        }
    }

    fun onGoogleStarted() = _ui.update { it.copy(step = Step.SigningIn, error = null) }

    fun onGoogleResult(result: GoogleSignIn.Result) {
        when (result) {
            GoogleSignIn.Result.Cancelled -> _ui.update { it.copy(step = Step.NeedsSignIn) }
            is GoogleSignIn.Result.Failed -> _ui.update { it.copy(step = Step.NeedsSignIn, error = result.message) }
            is GoogleSignIn.Result.Token -> {
                val expected = _ui.value.email
                // Signing in with another Google account would switch accounts, not re-confirm this one.
                if (result.email != null && expected != null && !result.email.equals(expected, ignoreCase = true)) {
                    _ui.update {
                        it.copy(step = Step.NeedsSignIn, error = "That's a different Google account. Pick $expected, the one you're signed in with.")
                    }
                    return
                }
                signInAgain(failureStep = Step.NeedsSignIn) { auth.signInWithGoogleIdToken(result.idToken) }
            }
        }
    }

    fun sendSignInLink() {
        val email = _ui.value.email ?: return
        _ui.update { it.copy(step = Step.SigningIn, error = null) }
        scope.launch {
            try {
                auth.sendEmailSignInLink(email)
                _ui.update { it.copy(step = Step.CheckInbox(email)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(step = Step.NeedsSignIn, error = "Couldn't send the link: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /**
     * An email sign-in link arrived while signed in. True if this flow was waiting for it (then
     * it's used to sign in again); false means nobody needed it.
     */
    fun onEmailLink(link: String): Boolean {
        val step = _ui.value.step
        val email = (step as? Step.CheckInbox)?.email ?: _ui.value.email.takeIf { step == Step.NeedsSignIn } ?: return false
        signInAgain(failureStep = Step.CheckInbox(email), failure = "That sign-in link didn't work. It may have expired; send a new one.") {
            auth.completeEmailLinkSignIn(link, email)
        }
        return true
    }

    private fun signInAgain(failureStep: Step, failure: String = "Sign-in didn't work", block: suspend () -> Unit) {
        _ui.update { it.copy(step = Step.SigningIn, error = null) }
        scope.launch {
            try {
                block()
                if (auth.currentUid != targetUid) {
                    _ui.update {
                        it.copy(
                            step = Step.NeedsSignIn,
                            error = "That signed you in to a different account, so nothing was deleted. " +
                                "Sign in with ${it.email ?: "the account you want to delete"} to go on.",
                        )
                    }
                } else {
                    _ui.update { it.copy(step = Step.Confirm, signedInAgain = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(step = failureStep, error = "$failure: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun undoPrepare() {
        if (!prepared) return
        prepared = false
        scope.launch { hooks.abandon() }
    }

    companion object {
        const val CONFIRM_WORD = "DELETE"

        /** The username (with or without @) or DELETE, ignoring case and surrounding spaces. */
        fun matchesConfirmation(typed: String, username: String?): Boolean {
            val t = typed.trim().removePrefix("@")
            if (t.isEmpty()) return false
            return t.equals(CONFIRM_WORD, ignoreCase = true) || (username != null && t.equals(username, ignoreCase = true))
        }

        fun create(container: AppContainer, appContext: Context) = DeleteAccountViewModel(
            auth = container.social.auth,
            myUsername = container.social.profiles.observeMyProfile().map { it?.username },
            hooks = ContainerDeletionHooks(container, appContext.applicationContext),
            // What viewModelScope would be; passed in so tests can use their own.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}

/** The real hooks: same room/sync teardown as signing out, plus per-account local data. */
private class ContainerDeletionHooks(
    private val container: AppContainer,
    private val appContext: Context,
) : AccountDeletionHooks {

    /** The room whose sync [beforeDelete] stopped; left by [abandon] if the account survives. */
    private var stoppedRoom: String? = null

    override suspend fun beforeDelete() {
        stoppedRoom = container.sessionController.state.value.roomCode
        container.sessionController.stop()
        ChatBubbleService.stop(appContext)
        container.socialSession.pauseForAccountDeletion()
    }

    override suspend fun afterDelete(uid: String) {
        // The eraser already left the room server-side; only local room state remains.
        stoppedRoom = null
        container.prefs.clearAccount(uid) // also lastRoomCode / pendingJoinCode
        container.watchlist.clear()
        container.socialSession.endAccountDeletion()
        PushNotifications.deleteLocalToken()
    }

    override suspend fun abandon() {
        stoppedRoom?.let { room ->
            runCatching { container.transport.leaveRoom(room) }
            container.prefs.lastRoomCode = null
        }
        stoppedRoom = null
        container.socialSession.endAccountDeletion()
    }
}
