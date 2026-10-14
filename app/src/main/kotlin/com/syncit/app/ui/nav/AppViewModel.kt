package com.syncit.app.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.model.AuthState
import com.syncit.core.model.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Which part of the app a user may see. Signed-out users only get the account flow. */
sealed interface Gate {
    data object Loading : Gate
    data object SignedOut : Gate
    data object ProfileError : Gate

    /** Signed in, no username yet. [seedName] = the Google/email name for suggestions. */
    data class NeedsUsername(val seedName: String?) : Gate
    data class NeedsActivitySetup(val uid: String) : Gate
    data object NeedsPermissions : Gate
    data object Ready : Gate
}

/** Our profile while it loads. */
sealed interface ProfileLoad {
    data object Loading : ProfileLoad
    data object Failed : ProfileLoad
    data class Loaded(val profile: UserProfile?) : ProfileLoad
}

/** First run: sign in → username → activity setup → permissions → Home. */
fun computeGate(
    auth: AuthState,
    profile: ProfileLoad,
    activitySetupFor: String?,
    onboarded: Boolean,
): Gate = when (auth) {
    AuthState.Loading -> Gate.Loading
    AuthState.SignedOut -> Gate.SignedOut
    is AuthState.SignedIn -> when (profile) {
        ProfileLoad.Loading -> Gate.Loading
        ProfileLoad.Failed -> Gate.ProfileError
        is ProfileLoad.Loaded -> when {
            profile.profile == null -> Gate.NeedsUsername(auth.displayName ?: auth.email?.substringBefore('@'))
            activitySetupFor != auth.uid -> Gate.NeedsActivitySetup(auth.uid)
            !onboarded -> Gate.NeedsPermissions
            else -> Gate.Ready
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(container: AppContainer) : ViewModel() {
    private val social = container.social
    private val prefs = container.prefs

    /** Bumped by "Try again" after the profile failed to load. */
    private val retry = MutableStateFlow(0)

    /**
     * Built per auth state (flatMapLatest), so a fresh sign-in never pairs with the previous
     * account's profile and briefly shows the wrong screen.
     */
    val gate: StateFlow<Gate> = social.auth.state.flatMapLatest { auth ->
        if (auth !is AuthState.SignedIn) {
            flowOf(computeGate(auth, ProfileLoad.Loading, null, false))
        } else {
            val profile = retry.flatMapLatest {
                social.profiles.observeMyProfile()
                    .map<UserProfile?, ProfileLoad> { ProfileLoad.Loaded(it) }
                    .onStart { emit(ProfileLoad.Loading) }
                    .catch { emit(ProfileLoad.Failed) }
            }.onEach { load ->
                // Rooms, room chat and the chat bubble read the name from prefs.
                (load as? ProfileLoad.Loaded)?.profile?.displayName?.takeIf { it.isNotBlank() }
                    ?.let { prefs.displayName = it }
            }
            combine(profile, prefs.activitySetupFor, prefs.onboarded) { load, setupFor, onboarded ->
                computeGate(auth, load, setupFor, onboarded)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Gate.Loading)

    fun retryProfile() {
        retry.value++
    }
}
