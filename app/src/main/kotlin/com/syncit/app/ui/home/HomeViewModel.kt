package com.syncit.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.config.RoomLinks
import com.syncit.core.transport.RoomNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val name: String,
        val code: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val lastRoom: String? = null,
    ) {
        val codeValid: Boolean get() = code.length == RoomLinks.CODE_LENGTH
    }

    private val _ui = MutableStateFlow(
        UiState(name = container.prefs.displayName, lastRoom = container.prefs.lastRoomCode),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun onNameChange(value: String) = _ui.update { it.copy(name = value.take(40), error = null) }

    /** Upper-cases and drops characters that can't appear in a code. */
    fun onCodeChange(value: String) = _ui.update {
        it.copy(
            code = value.uppercase().filter { c -> c in RoomLinks.CODE_ALPHABET }.take(RoomLinks.CODE_LENGTH),
            error = null,
        )
    }

    fun create(onJoined: (String) -> Unit) = runBusy {
        val code = container.transport.createRoom(saveName())
        container.prefs.lastRoomCode = code
        onJoined(code)
    }

    fun join(code: String = _ui.value.code, onJoined: (String) -> Unit) = runBusy {
        container.transport.joinRoom(code, saveName())
        container.prefs.lastRoomCode = code
        onJoined(code)
    }

    private fun saveName(): String = _ui.value.name.trim().also { container.prefs.displayName = it }

    private fun runBusy(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        if (_ui.value.name.isBlank()) {
            _ui.update { it.copy(error = "Enter your name first") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomNotFoundException) {
                _ui.update { it.copy(error = "No room with that code. Check it with the host.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Couldn't connect: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}
