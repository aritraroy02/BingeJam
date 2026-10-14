package com.syncit.media.activity

import android.os.SystemClock
import android.util.Log
import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.social.ActivityTracker
import com.syncit.core.social.ActivityUpdate
import com.syncit.core.social.PresenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ActivityTracker] on top of the [MediaControllerGateway]: reads only MediaSession state and
 * metadata (never notifications) for the apps the user shares, and publishes through
 * [PresenceRepository]. Decisions live in [ActivityDecider]; this class only feeds it inputs and
 * the clock, and calls the repository.
 *
 * Runs on [scope]'s dispatcher (use the main thread, like the gateway). [elapsedRealtimeMs] is
 * the local monotonic clock; it's injectable for tests and never wall-clock time.
 */
class AndroidActivityTracker(
    private val gateway: MediaControllerGateway,
    private val presence: PresenceRepository,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : ActivityTracker {

    private val _current = MutableStateFlow<Activity?>(null)
    override val current: StateFlow<Activity?> = _current.asStateFlow()

    private var job: Job? = null
    private var decider: ActivityDecider? = null

    private class Inputs(
        val sessions: List<MediaSessionInfo>,
        val shared: Set<ActivityApp>,
        val roomCode: String?,
    )

    override fun start(sharedApps: StateFlow<Set<ActivityApp>>, currentRoomCode: StateFlow<String?>) {
        job?.cancel()
        val decider = ActivityDecider().also { this.decider = it }
        job = scope.launch {
            val inputs: ReceiveChannel<Inputs> = combine(gateway.sessions, sharedApps, currentRoomCode, ::Inputs)
                .conflate()
                .produceIn(this)
            var latest: Inputs? = null
            // One loop owns the decider: re-evaluate on every input change and whenever a
            // debounce, retry or idle deadline passes (time alone can change the decision).
            while (isActive) {
                val now = elapsedRealtimeMs()
                latest?.let { decider.update(it.sessions, it.shared, it.roomCode, now) }
                _current.value = decider.desired?.toActivity()

                decider.due(now)?.let { due -> publish(decider, due.update) }

                val waitMs = decider.nextCheckInMs(elapsedRealtimeMs())
                val next = withTimeoutOrNull(waitMs) { inputs.receiveCatching().getOrNull() }
                if (next != null) latest = next
            }
        }
    }

    override fun stop() {
        val decider = decider
        job?.cancel()
        job = null
        this.decider = null
        _current.value = null
        // Best effort: don't leave "now playing" behind. Call stop() before signing out.
        if (decider?.hasPublishedActivity == true) {
            scope.launch { safely { presence.publishActivity(null) } }
        }
    }

    private suspend fun publish(decider: ActivityDecider, update: ActivityUpdate?) {
        val ok = safely { presence.publishActivity(update) }
        if (ok) decider.onPublished(update) else decider.onPublishFailed(elapsedRealtimeMs())
    }

    /** Publishing must never crash the tracker: log and let the decider retry later. */
    private suspend fun safely(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "publishActivity failed", e)
        false
    }

    private fun ActivityUpdate.toActivity() = Activity(
        app = app,
        title = metadata?.title,
        subtitle = metadata?.subtitle,
        artworkJpeg = metadata?.artworkJpeg,
        playing = playing,
        durationMs = metadata?.durationMs,
        // Server-stamped by the repository when published; not known locally.
        startedAt = 0L,
        roomCode = roomCode,
    )

    private companion object {
        const val TAG = "SyncItActivity"
    }
}
