package com.syncit.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.data.UpdateChecker
import com.syncit.app.di.AppContainer
import com.syncit.app.push.PushNotifications
import com.syncit.app.social.ActivityText
import com.syncit.app.social.AvatarImages
import com.syncit.app.social.UsernameAvailability
import com.syncit.app.social.UsernameRules
import com.syncit.core.model.Activity
import com.syncit.core.model.AuthState
import com.syncit.core.model.UserProfile
import com.syncit.core.social.AvatarException
import com.syncit.core.social.UsernameTakenException
import com.syncit.core.voice.VoiceDevices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val social = container.social
    val prefs = container.prefs

    val profile: StateFlow<UserProfile?> = social.profiles.observeMyProfile()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val auth: StateFlow<AuthState> = social.auth.state

    /** Mics and speakers for room voice chat, with the user's picks. */
    val voiceDevices: StateFlow<VoiceDevices> = container.voice.devices

    fun setVoiceInput(id: String?) = container.voice.setInputDevice(id)

    fun setVoiceOutput(id: String?) = container.voice.setOutputDevice(id)

    /** What the tracker would share right now (preview under the toggles). */
    val sharingNow: StateFlow<Activity?> = social.activityTracker.current

    fun describe(activity: Activity): String = ActivityText.activityLine(activity)

    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    /** "Check for updates" on the version row. */
    sealed interface UpdateCheck {
        data object Idle : UpdateCheck
        data object Checking : UpdateCheck
        data class Done(val result: UpdateChecker.Result) : UpdateCheck
    }

    private val _updateCheck = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val updateCheck: StateFlow<UpdateCheck> = _updateCheck.asStateFlow()

    fun checkForUpdate() {
        if (_updateCheck.value == UpdateCheck.Checking) return
        viewModelScope.launch {
            _updateCheck.value = UpdateCheck.Checking
            _updateCheck.value = UpdateCheck.Done(UpdateChecker.checkNow())
        }
    }

    /** Leaves any room (best effort), stops syncing, then signs out; the app returns to Welcome. */
    fun signOut(beforeSignOut: () -> Unit) {
        if (_signingOut.value) return
        _signingOut.value = true
        viewModelScope.launch {
            val room = container.sessionController.state.value.roomCode
            container.sessionController.stop()
            beforeSignOut()
            if (room != null) runCatching { container.transport.leaveRoom(room) }
            container.prefs.lastRoomCode = null
            container.prefs.pendingJoinCode = null
            // The tracker must stop before the account goes (its last write needs the token);
            // FirebaseServices takes presence offline itself just before signing out.
            social.activityTracker.stop()
            PushNotifications.unregisterToken(social.push)
            runCatching { social.auth.signOut() }
            _signingOut.value = false
        }
    }
}

/** Edit profile picture, username (live availability), display name and bio. */
class EditProfileViewModel(container: AppContainer) : ViewModel() {
    private val profiles = container.social.profiles
    private val avatars = container.social.avatars

    data class UiState(
        val loaded: Boolean = false,
        val username: String = "",
        val displayName: String = "",
        val bio: String = "",
        val photoUrl: String? = null,
        val photoBusy: Boolean = false,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var current: UserProfile? = null

    val availability = UsernameAvailability(viewModelScope, profiles::checkUsername, currentUsername = { current?.username })

    init {
        viewModelScope.launch {
            val me = runCatching { profiles.observeMyProfile().first() }.getOrNull()
            current = me
            _ui.update {
                it.copy(
                    loaded = true,
                    username = me?.username.orEmpty(),
                    displayName = me?.displayName.orEmpty(),
                    bio = me?.bio.orEmpty(),
                    photoUrl = me?.photoUrl,
                )
            }
            availability.onInput(me?.username.orEmpty())
        }
    }

    /** Uploads the picked picture (GIFs stay animated) and puts it on our profile right away. */
    fun changePhoto(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        photoAction("Couldn't change your picture") {
            val url = avatars.upload(AvatarImages.prepare(appContext, uri))
            profiles.updatePhoto(url)
            _ui.update { it.copy(photoUrl = url) }
        }
    }

    fun removePhoto() = photoAction("Couldn't remove your picture") {
        profiles.updatePhoto(null)
        _ui.update { it.copy(photoUrl = null) }
        // A Google photo isn't stored by us; deleting a missing picture is harmless.
        runCatching { avatars.delete() }
    }

    private fun photoAction(failure: String, block: suspend () -> Unit) {
        if (_ui.value.photoBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(photoBusy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AvatarException) {
                _ui.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "$failure: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(photoBusy = false) }
            }
        }
    }

    fun onUsernameChange(raw: String) {
        val value = UsernameRules.filterInput(raw)
        _ui.update { it.copy(username = value, error = null, saved = false) }
        availability.onInput(value)
    }

    fun onDisplayNameChange(value: String) = _ui.update { it.copy(displayName = value.take(40), error = null, saved = false) }

    fun onBioChange(value: String) = _ui.update { it.copy(bio = value.take(MAX_BIO), error = null, saved = false) }

    fun save(onSaved: () -> Unit) {
        val state = _ui.value
        val username = availability.usable ?: return
        val displayName = state.displayName.trim()
        if (displayName.isEmpty() || state.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            try {
                // Renaming claims the new name and releases the old one atomically (ProfileRepository).
                if (username != current?.username) profiles.claimUsername(username, displayName)
                profiles.updateProfile(displayName, state.bio.trim().ifEmpty { null })
                _ui.update { it.copy(saved = true) }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: UsernameTakenException) {
                availability.markTaken()
                _ui.update { it.copy(error = "Someone just took @$username. Try another.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Couldn't save: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        const val MAX_BIO = 160
    }
}
