package com.syncit.media.service

import android.os.SystemClock
import android.util.Log
import com.syncit.core.model.EngineOutput
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.Room
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncCommand
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncSessionState
import com.syncit.core.sync.SyncClock
import com.syncit.core.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wires the gateway, the transport and a [SyncEngine] together for one room. Runs entirely on
 * the [scope]'s dispatcher (main thread), so the engine is only ever called from one thread.
 * Depends only on :core interfaces.
 */
internal class RoomSyncCoordinator(
    private val deps: SyncDependencies,
    private val scope: CoroutineScope,
    private val publish: (SyncSessionState) -> Unit,
) {
    private var job: Job? = null
    private var roomCode: String? = null
    private var engine: SyncEngine? = null
    private var room: Room? = null
    private var session: MediaSessionInfo? = null
    private var state = SyncSessionState()

    fun start(code: String) {
        if (code == roomCode && job?.isActive == true) return
        stop()
        roomCode = code
        state = SyncSessionState(roomCode = code, status = SyncStatus.WAITING_FOR_ROOM)
        publish(state)

        job = scope.launch {
            val transport = deps.transport
            val uid = try {
                transport.ensureSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(lastError = "Sign-in failed: ${e.message}")
                publish(state)
                return@launch
            }

            val clock = object : SyncClock {
                // Local monotonic clock; same base as PlaybackState.getLastPositionUpdateTime().
                override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

                // Server time from the transport (Firebase offset anchored to elapsedRealtime).
                override fun serverNowMs(): Long = transport.serverClock.serverNowMs()
            }
            val engine = deps.engineFactory.create(uid, clock).also { this@RoomSyncCoordinator.engine = it }

            // One Firebase listener shared by both consumers below.
            val roomFlow = transport.observeRoom(code)
                .catch { emit(null) }
                .shareIn(this, SharingStarted.Eagerly, replay = 1)

            launch {
                roomFlow.collect { r ->
                    room = r
                    if (r != null) execute(code, engine.onRoom(r.state, r.members))
                    publishState(engine)
                }
            }

            launch {
                // The target app follows the room's title once it's set (Phase 4).
                val targetFlow = roomFlow.map { it?.title?.service }.distinctUntilChanged()
                combine(deps.gateway.sessions, targetFlow) { sessions, target -> pickSession(sessions, target) }
                    .distinctUntilChanged()
                    .collect { picked ->
                        session = picked
                        execute(code, engine.onLocalSnapshot(picked?.snapshot))
                        publishState(engine)
                    }
            }

            launch {
                while (isActive) {
                    delay(TICK_MS)
                    execute(code, engine.onTick())
                    publishState(engine)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        roomCode = null
        engine = null
        room = null
        session = null
        state = SyncSessionState()
        publish(state)
    }

    /**
     * The session to sync: the room's chosen service if set, otherwise whichever configured
     * service (Netflix, or JioHotstar once its package is filled in) has an active session.
     */
    private fun pickSession(sessions: List<MediaSessionInfo>, target: StreamingService?): MediaSessionInfo? {
        val packages = (if (target != null) listOf(target) else StreamingService.entries)
            .filter { it.isConfigured }
            .map { it.packageName }
        return sessions.firstOrNull { it.packageName in packages }
    }

    /** Applies commands locally and sends broadcasts/reports to the room. */
    private fun execute(code: String, output: EngineOutput) {
        val key = session?.sessionKey
        if (key != null) {
            for (command in output.commands) {
                val ok = when (command) {
                    SyncCommand.Play -> deps.gateway.play(key)
                    SyncCommand.Pause -> deps.gateway.pause(key)
                    is SyncCommand.SeekTo -> deps.gateway.seekTo(key, command.positionMs)
                }
                if (!ok) Log.w(TAG, "Command $command not applied (session gone)")
            }
        }
        output.broadcast?.let { broadcast ->
            scope.launch { safely("writeState") { deps.transport.writeState(code, broadcast) } }
        }
        output.positionReport?.let { position ->
            scope.launch { safely("reportPosition") { deps.transport.reportPosition(code, position) } }
        }
    }

    private fun publishState(engine: SyncEngine) {
        val target = room?.title?.service ?: session?.packageName?.let { StreamingService.forPackage(it) }
        val status = when {
            !deps.gateway.notificationAccessGranted.value -> SyncStatus.NO_NOTIFICATION_ACCESS
            target != null && !target.isConfigured -> SyncStatus.NOT_CONFIGURED
            room == null -> SyncStatus.WAITING_FOR_ROOM
            else -> engine.status
        }
        val next = state.copy(status = status, mode = engine.mode, driftMs = engine.lastDriftMs, service = target)
        if (next != state) {
            state = next
            publish(next)
        }
    }

    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            state = state.copy(lastError = "$what failed: ${e.message}")
            publish(state)
        }
    }

    private companion object {
        const val TAG = "SyncItCoordinator"
        const val TICK_MS = 1_000L
    }
}
