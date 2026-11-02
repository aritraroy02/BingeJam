package com.syncit.app.social

import android.os.SystemClock
import android.util.Log
import com.syncit.app.di.SocialServices
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionController
import com.syncit.core.session.SyncSessionState
import com.syncit.core.transport.SyncTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Writes "watched together" cards into DMs while we're in a room with friends. Lives with the
 * process (AppContainer), driven by the sync session, so it keeps counting when the room screen
 * isn't visible (Home, Netflix in front). Only while the session runs: no session, no cards.
 *
 * Trigger: every room/preview/friends change and every [WatchTogether.TICK_MS] it samples who is
 * together ([WatchTogether.sample]); [WatchTogetherTracker] adds up the time per friend and asks
 * for a write once a pair has [WatchTogether.MIN_TOGETHER_MS], then every
 * [WatchTogether.FLUSH_EVERY_MS], and once more when they stop being together. Each write creates
 * or extends one card per session; both phones write the same card (deterministic key), the
 * larger duration wins. Writes go out one at a time, in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherRecorder(
    private val social: SocialServices,
    sessionController: SyncSessionController,
    private val transport: SyncTransport,
    scope: CoroutineScope,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val tracker = WatchTogetherTracker()
    private val writes = Channel<WatchTogetherTracker.Flush>(Channel.UNLIMITED)

    private data class Inputs(val room: Room?, val preview: RoomPreview?, val friends: Set<String>)

    init {
        val roomInputs = sessionController.state
            .map { it.roomCode }
            .distinctUntilChanged()
            .flatMapLatest { code ->
                if (code == null) {
                    flowOf(null)
                } else {
                    val friends = social.friends.observeFriends()
                        .map { list -> list.map { it.profile.uid }.toSet() }
                        .distinctUntilChanged()
                        .catch { emit(emptySet()) }
                    combine(transport.observeRoom(code), transport.observePreview(code), friends) { room, preview, f ->
                        Inputs(room, preview, f) as Inputs?
                    }.catch { emit(null) }
                }
            }
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(WatchTogether.TICK_MS)
            }
        }
        scope.launch {
            combine(sessionController.state, roomInputs, ticker) { state, inputs, _ -> state to inputs }
                .collect { (state, inputs) -> step(state, inputs) }
        }
        scope.launch {
            for (flush in writes) {
                try {
                    val duration = social.dms.recordWatchedTogether(flush.friendUid, flush.report)
                    if (duration != null) tracker.adopt(flush.friendUid, flush.report.sessionKey, duration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort: the next write for this session tries again.
                    Log.w(TAG, "Watched-together card not written", e)
                }
            }
        }
    }

    private fun step(state: SyncSessionState, inputs: Inputs?) {
        val me = social.auth.currentUid?.let { transport.currentUid.value ?: it }
        val now = inputs?.takeIf { state.roomCode != null && inputs.room?.code == state.roomCode }?.let { i ->
            WatchTogether.sample(
                onTitle = state.status in ON_TITLE,
                room = i.room,
                preview = i.preview,
                fallbackService = state.service,
                me = me,
                friends = i.friends,
                serverNowMs = transport.serverClock.serverNowMs(),
            )
        }
        for (flush in tracker.step(elapsedRealtime(), now)) writes.trySend(flush)
    }

    private companion object {
        const val TAG = "SyncItWatched"

        /** On the room's title (drift corrections included), like SocialSession's watch-party flag. */
        val ON_TITLE = setOf(SyncStatus.SYNCING, SyncStatus.CORRECTING, SyncStatus.CATCHING_UP)
    }
}
