package com.syncit.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.NowPlayingMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [MediaControllerGateway] backed by MediaSessionManager. All controller bookkeeping happens on
 * the main thread.
 */
class AndroidMediaControllerGateway(context: Context) : MediaControllerGateway {

    private val appContext = context.applicationContext
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = MediaListenerService.componentName(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _access = MutableStateFlow(false)
    override val notificationAccessGranted: StateFlow<Boolean> = _access.asStateFlow()

    private val _sessions = MutableStateFlow<List<MediaSessionInfo>>(emptyList())
    override val sessions: StateFlow<List<MediaSessionInfo>> = _sessions.asStateFlow()

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback) {
        /** Last mapped metadata, so the artwork is only re-encoded when the app changes it. */
        var metadataSource: MediaMetadata? = null
        var metadata: NowPlayingMetadata? = null
    }

    /** sessionKey -> controller, in MediaSessionManager's priority order. */
    private val tracked = LinkedHashMap<String, Tracked>()
    private var listenerRegistered = false

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        // Fires when an app (e.g. Netflix) opens, closes or recreates its session, so we
        // re-attach automatically.
        onActiveSessionsChanged(controllers.orEmpty())
    }

    init {
        MediaListenerService.connectionCallback = { refresh() }
        refresh()
    }

    override fun refresh() {
        mainHandler.post { refreshOnMain() }
    }

    private fun refreshOnMain() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
        _access.value = granted
        if (!granted) {
            unregisterSessionsListener()
            onActiveSessionsChanged(emptyList())
            return
        }
        try {
            if (!listenerRegistered) {
                sessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener, listenerComponent, mainHandler,
                )
                listenerRegistered = true
            }
            onActiveSessionsChanged(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // Access was revoked between the check and the call.
            Log.w(TAG, "Notification access not usable yet", e)
            _access.value = false
            listenerRegistered = false
            onActiveSessionsChanged(emptyList())
        }
    }

    private fun unregisterSessionsListener() {
        if (!listenerRegistered) return
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        listenerRegistered = false
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>) {
        val incoming = LinkedHashMap<String, MediaController>()
        for (controller in controllers) incoming[keyFor(controller)] = controller

        // Detach sessions that went away.
        val gone = tracked.keys - incoming.keys
        for (key in gone) {
            tracked.remove(key)?.let { it.controller.unregisterCallback(it.callback) }
        }

        // Attach new ones, keeping MediaSessionManager's order.
        val reordered = LinkedHashMap<String, Tracked>()
        for ((key, controller) in incoming) {
            reordered[key] = tracked[key] ?: attach(key, controller)
        }
        tracked.clear()
        tracked.putAll(reordered)
        publish()
    }

    private fun attach(key: String, controller: MediaController): Tracked {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

            override fun onSessionDestroyed() {
                tracked.remove(key)?.let { it.controller.unregisterCallback(it.callback) }
                publish()
            }
        }
        controller.registerCallback(callback, mainHandler)
        return Tracked(controller, callback)
    }

    private fun publish() {
        val list = tracked.map { (key, t) ->
            MediaSessionInfo(
                sessionKey = key,
                packageName = t.controller.packageName,
                snapshot = t.controller.playbackState?.let(PlaybackStateMapper::toSnapshot),
                metadata = metadataOf(t),
            )
        }
        _sessions.value = list
        DiagnosticsLog.logSessions(list)
    }

    private fun metadataOf(t: Tracked): NowPlayingMetadata? {
        val current = t.controller.metadata
        // MediaMetadata is immutable; apps publish a new instance on every change.
        if (current !== t.metadataSource) {
            t.metadataSource = current
            t.metadata = MetadataMapper.toNowPlaying(current)
        }
        return t.metadata
    }

    override fun play(sessionKey: String): Boolean =
        withController(sessionKey) { it.transportControls.play() }

    override fun pause(sessionKey: String): Boolean =
        withController(sessionKey) { it.transportControls.pause() }

    override fun seekTo(sessionKey: String, positionMs: Long): Boolean =
        withController(sessionKey) { it.transportControls.seekTo(positionMs.coerceAtLeast(0L)) }

    private inline fun withController(sessionKey: String, block: (MediaController) -> Unit): Boolean {
        val controller = tracked[sessionKey]?.controller ?: return false
        return try {
            block(controller)
            true
        } catch (e: RuntimeException) {
            // A session that died between lookup and call throws; treat as "not applied".
            Log.w(TAG, "Command to $sessionKey failed", e)
            false
        }
    }

    private fun keyFor(controller: MediaController): String =
        "${controller.packageName}#${Integer.toHexString(controller.sessionToken.hashCode())}"

    private companion object {
        const val TAG = "SyncItGateway"
    }
}
