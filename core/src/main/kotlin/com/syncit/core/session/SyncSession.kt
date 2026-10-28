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
    /** Jams only: where following the host's track stands (see [JamFollow]). */
    val jamFollow: JamFollow = JamFollow.IDLE,
)

/**
 * Jams (music/YouTube rooms): members automatically start whatever the host is playing.
 * IDLE = nothing to do (on the host's track, or not a jam). STARTING = we asked the member's app
 * to play the host's track. NEEDS_APP = the member's app has no media session yet, so the UI must
 * open it (a background service can't launch apps); following continues once it appears.
 * FAILED = the app ignored the request; the UI offers "Back to the jam" ([SyncSessionController.followHost]).
 */
enum class JamFollow { IDLE, STARTING, NEEDS_APP, FAILED }

/** Starts/stops the background sync session (a foreground service in :media). */
interface SyncSessionController {
    val state: StateFlow<SyncSessionState>
    fun start(roomCode: String)
    fun stop()

    /** Jams: start the host's current track on this phone now ("Back to the jam"). No-op otherwise. */
    fun followHost()
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

/**
 * Implemented by the Application: "Quit" from the sync notification. Stops everything that runs
 * in the background (sync, chat bubble, presence, activity sharing) until the app is opened again.
 */
interface AppQuitHandler {
    fun quitApp()
}
