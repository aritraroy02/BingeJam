package com.syncit.core.session

import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus
import com.syncit.core.sync.SyncEngineFactory
import com.syncit.core.transport.SyncTransport
import kotlinx.coroutines.flow.StateFlow

/** What the UI shows about the running sync session. */
data class SyncSessionState(
    val roomCode: String? = null,
    val status: SyncStatus = SyncStatus.IDLE,
    val mode: SyncMode? = null,
    val driftMs: Long? = null,
    val service: StreamingService? = null,
    val lastError: String? = null,
)

/** Starts/stops the background sync session (a foreground service in :media). */
interface SyncSessionController {
    val state: StateFlow<SyncSessionState>
    fun start(roomCode: String)
    fun stop()
}

/** What the sync session needs; the app's DI container provides it. */
interface SyncDependencies {
    val gateway: MediaControllerGateway
    val transport: SyncTransport
    val engineFactory: SyncEngineFactory
}

/** Implemented by the Application so services can reach [SyncDependencies]. */
interface SyncDependenciesProvider {
    val syncDependencies: SyncDependencies
}
