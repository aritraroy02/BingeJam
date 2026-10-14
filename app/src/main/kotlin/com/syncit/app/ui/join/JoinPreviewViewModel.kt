package com.syncit.app.ui.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.model.RoomPreview
import com.syncit.core.transport.RoomNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What an invitee sees before joining: the room's preview, then Join. With [autoJoin] (a friend
 * accepted our "Ask to join", or we tapped Join on their invite) it joins straight away.
 */
class JoinPreviewViewModel(
    val code: String,
    private val container: AppContainer,
    autoJoin: Boolean = false,
) : ViewModel() {

    sealed interface PreviewState {
        data object Loading : PreviewState

        /** [preview] is null for rooms created before previews existed, or a wrong code. */
        data class Loaded(val preview: RoomPreview?) : PreviewState
    }

    val preview: StateFlow<PreviewState> = container.transport.observePreview(code)
        .map<RoomPreview?, PreviewState> { PreviewState.Loaded(it) }
        .catch { emit(PreviewState.Loaded(null)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreviewState.Loading)

    /** [joined] is set once we're a member; the screen then navigates to the room. */
    data class JoinState(val busy: Boolean = false, val error: String? = null, val joined: Boolean = false)

    private val _join = MutableStateFlow(JoinState())
    val join: StateFlow<JoinState> = _join.asStateFlow()

    init {
        if (autoJoin) join()
    }

    fun join() {
        if (_join.value.busy) return
        viewModelScope.launch {
            _join.update { JoinState(busy = true) }
            try {
                container.transport.joinRoom(code, container.prefs.displayName.ifBlank { "Guest" })
                container.prefs.lastRoomCode = code
                container.prefs.pendingJoinCode = null
                _join.update { JoinState(joined = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomNotFoundException) {
                _join.update { JoinState(error = "No room with code $code. Check it with the host.") }
            } catch (e: Exception) {
                _join.update { JoinState(error = "Couldn't join: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** "Not now": forget an invite that was waiting for onboarding. */
    fun dismiss() {
        container.prefs.pendingJoinCode = null
    }
}
