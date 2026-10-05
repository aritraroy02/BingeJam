package com.syncit.app.ui.diagnostics

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.model.MediaSessionInfo
import com.syncit.sync.PositionMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class DiagnosticsViewModel(private val gateway: MediaControllerGateway) : ViewModel() {

    val accessGranted: StateFlow<Boolean> = gateway.notificationAccessGranted
    val sessions: StateFlow<List<MediaSessionInfo>> = gateway.sessions

    /** elapsedRealtime, ticking 4x a second so the live position column moves. */
    val nowElapsed: StateFlow<Long> = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(250)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SystemClock.elapsedRealtime())

    private val _selectedKey = MutableStateFlow<String?>(null)
    val selectedKey: StateFlow<String?> = _selectedKey.asStateFlow()

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun refresh() = gateway.refresh()

    fun select(key: String) {
        _selectedKey.value = key
    }

    fun play() = send("play") { gateway.play(it.sessionKey) }

    fun pause() = send("pause") { gateway.pause(it.sessionKey) }

    fun seekForward30() = send("seek +30s") { session ->
        val snapshot = session.snapshot ?: return@send false
        val now = PositionMath.localLivePositionMs(snapshot, SystemClock.elapsedRealtime())
        if (now < 0) return@send false
        gateway.seekTo(session.sessionKey, now + 30_000)
    }

    private fun send(label: String, action: (MediaSessionInfo) -> Boolean) {
        val session = sessions.value.firstOrNull { it.sessionKey == _selectedKey.value }
        _lastResult.value = when {
            session == null -> "Select a session first"
            action(session) -> "Sent $label to ${session.packageName}. Watch whether its state/position changes."
            else -> "Couldn't send $label (session gone or position unknown)"
        }
    }
}
