package com.syncit.media.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.syncit.core.session.SyncSessionController
import com.syncit.core.session.SyncSessionState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide state of the running sync session, written by [SyncForegroundService]. */
internal object SyncSessionStateHolder {
    val state = MutableStateFlow(SyncSessionState())

    /**
     * "Back to the jam" requests from the UI, collected by the running [SyncForegroundService]
     * on the main thread. In-process, so no service intent (which could hit background-start
     * limits); dropped if no session is running.
     */
    val followRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

class AndroidSyncSessionController(context: Context) : SyncSessionController {
    private val appContext = context.applicationContext

    override val state: StateFlow<SyncSessionState> = SyncSessionStateHolder.state.asStateFlow()

    override fun start(roomCode: String) {
        try {
            ContextCompat.startForegroundService(appContext, SyncForegroundService.startIntent(appContext, roomCode))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses to start a foreground service from the background
            // (ForegroundServiceStartNotAllowedException). We only call this from the visible room screen.
            Log.w("SyncItSession", "Couldn't start sync service", e)
            SyncSessionStateHolder.state.value = state.value.copy(lastError = "Couldn't start syncing: ${e.message}")
        }
    }

    override fun stop() {
        appContext.stopService(Intent(appContext, SyncForegroundService::class.java))
    }

    override fun followHost() {
        SyncSessionStateHolder.followRequests.tryEmit(Unit)
    }
}
