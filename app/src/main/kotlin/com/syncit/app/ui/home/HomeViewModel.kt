package com.syncit.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.config.RoomLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val code: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val lastRoom: String? = null,
    ) {
        val codeValid: Boolean get() = code.length == RoomLinks.CODE_LENGTH
    }

    private val _ui = MutableStateFlow(UiState(lastRoom = container.prefs.lastRoomCode))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val name: String get() = container.prefs.displayName

    /** Upper-cases and drops characters that can't appear in a code. */
    fun onCodeChange(value: String) = _ui.update {
        it.copy(
            code = value.uppercase().filter { c -> c in RoomLinks.CODE_ALPHABET }.take(RoomLinks.CODE_LENGTH),
            error = null,
        )
    }

    fun refreshLastRoom() = _ui.update { it.copy(lastRoom = container.prefs.lastRoomCode) }

    fun create(onJoined: (String) -> Unit) {
        if (_ui.value.busy) return
        val name = container.prefs.displayName.trim()
        if (name.isBlank()) {
            _ui.update { it.copy(error = "Set your name in Settings first") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                val code = container.transport.createRoom(name)
                container.prefs.lastRoomCode = code
                onJoined(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Couldn't create a room: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}
