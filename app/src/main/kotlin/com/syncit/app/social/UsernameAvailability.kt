package com.syncit.app.social

import com.syncit.core.social.UsernameCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live "is this username free?" check for the username picker and Settings. Local format errors
 * show at once; the server check runs only after typing pauses for [debounceMs], and a newer
 * keystroke cancels an older check so a slow answer can't overwrite a newer one.
 *
 * Plain class (not a ViewModel) so it can be unit-tested with runBlocking.
 */
class UsernameAvailability(
    private val scope: CoroutineScope,
    private val check: suspend (String) -> UsernameCheck,
    /** Our current username (Settings): typing it again counts as fine, not "taken". */
    private val currentUsername: () -> String? = { null },
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    sealed interface Status {
        data object Empty : Status
        data class Invalid(val reason: String) : Status
        data object Checking : Status
        data class Available(val username: String) : Status
        data object Taken : Status
        data object Unchanged : Status
        data object Failed : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Empty)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var job: Job? = null

    /** The username to submit if [status] allows it, else null. */
    val usable: String?
        get() = when (val s = _status.value) {
            is Status.Available -> s.username
            Status.Unchanged -> currentUsername()
            else -> null
        }

    fun onInput(raw: String) {
        job?.cancel()
        val name = UsernameRules.normalize(raw)
        if (name.isEmpty()) {
            _status.value = Status.Empty
            return
        }
        UsernameRules.problem(name)?.let {
            _status.value = Status.Invalid(it)
            return
        }
        if (name == currentUsername()) {
            _status.value = Status.Unchanged
            return
        }
        _status.value = Status.Checking
        job = scope.launch {
            delay(debounceMs)
            _status.value = try {
                when (val result = check(name)) {
                    is UsernameCheck.Available -> Status.Available(result.normalized)
                    UsernameCheck.Taken -> Status.Taken
                    is UsernameCheck.Invalid -> Status.Invalid(result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Status.Failed
            }
        }
    }

    /** After a claim failed with "taken" (someone was faster). */
    fun markTaken() {
        job?.cancel()
        _status.value = Status.Taken
    }

    companion object {
        const val DEBOUNCE_MS = 350L
    }
}
