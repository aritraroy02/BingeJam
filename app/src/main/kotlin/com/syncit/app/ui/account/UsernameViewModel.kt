package com.syncit.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.UsernameAvailability
import com.syncit.app.social.UsernameRules
import com.syncit.app.ui.profile.BirthDates
import com.syncit.core.social.UsernameTakenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** First-run username picker: live availability, suggestions, editable display name. */
class UsernameViewModel(container: AppContainer, private val seedName: String?) : ViewModel() {
    private val profiles = container.social.profiles

    data class UiState(
        val username: String = "",
        val displayName: String = "",
        /** Required (2026-11-10), private: only you and the app's owner see it. */
        val birthDate: LocalDate? = null,
        val suggestions: List<String> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(
        // Only a Google display name pre-fills this; email-link users start empty.
        UiState(displayName = seedName.orEmpty().trim().take(MAX_DISPLAY_NAME)),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val availability = UsernameAvailability(viewModelScope, profiles::checkUsername)

    init {
        loadSuggestions()
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            val seed = seedName?.takeIf { it.isNotBlank() }
            val list = runCatching { profiles.suggestUsernames(seed) }.getOrDefault(emptyList())
            _ui.update { it.copy(suggestions = list) }
            // Pre-fill the first idea so most people can just tap Continue.
            if (_ui.value.username.isEmpty()) list.firstOrNull()?.let(::onUsernameChange)
        }
    }

    fun onUsernameChange(raw: String) {
        val value = UsernameRules.filterInput(raw)
        _ui.update { it.copy(username = value, error = null) }
        availability.onInput(value)
    }

    fun onBirthDateChange(value: LocalDate) = _ui.update { it.copy(birthDate = value, error = null) }

    fun onDisplayNameChange(value: String) = _ui.update { it.copy(displayName = value.take(MAX_DISPLAY_NAME), error = null) }

    /** Claiming makes observeMyProfile emit, which moves the app to the next step. */
    fun submit() {
        val username = availability.usable ?: return
        val displayName = _ui.value.displayName.trim()
        val birthDate = _ui.value.birthDate?.takeIf { BirthDates.problem(it) == null } ?: return
        if (displayName.isEmpty() || _ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                // Birthday first: claiming the username moves the app on to the next step.
                runCatching { profiles.setBirthDate(BirthDates.wire(birthDate)) }
                profiles.claimUsername(username, displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: UsernameTakenException) {
                availability.markTaken()
                _ui.update { it.copy(error = "Someone just took @$username. Try another.") }
                loadSuggestions()
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Couldn't save: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        const val MAX_DISPLAY_NAME = 40
    }
}
