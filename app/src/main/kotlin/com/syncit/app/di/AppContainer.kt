package com.syncit.app.di

import android.app.Application
import com.syncit.app.data.UserPrefs
import com.syncit.app.social.WatchTogetherRecorder
import com.syncit.core.lookup.TitleLookup
import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncSessionController
import com.syncit.core.sync.SyncEngineFactory
import com.syncit.core.transport.SyncTransport
import com.syncit.core.voice.VOICE_ECHO_ROOM
import com.syncit.core.voice.VoiceController
import com.syncit.core.voice.VoiceStatus
import com.syncit.firebase.FirebaseServices
import com.syncit.firebase.FirebaseSyncTransport
import com.syncit.media.AndroidMediaControllerGateway
import com.syncit.media.activity.AndroidActivityTracker
import com.syncit.media.service.AndroidSyncSessionController
import com.syncit.sync.DefaultSyncEngine
import com.syncit.voice.AndroidVoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual DI: the one place concrete implementations are chosen. Everything else sees only the
 * :core interfaces. To move to a Socket.IO backend, swap [transport] here.
 */
class AppContainer(app: Application) : SyncDependencies {
    /** Lives as long as the process (activity tracker, social session). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Real backend, unless the debug-only switch DebugSocial.USE_FAKE_SOCIAL (app/src/debug) asks
     * for the in-memory fakes. Release builds always take the real one.
     */
    private val firebase: FirebaseServices? = if (DebugSocial.USE_FAKE_SOCIAL) null else FirebaseServices.create(app)

    override val gateway: MediaControllerGateway = AndroidMediaControllerGateway(app)

    /** Shares the signed-in account with [social] when real; the standalone v1 transport otherwise. */
    override val transport: SyncTransport = firebase?.transport ?: FirebaseSyncTransport.create()
    override val engineFactory: SyncEngineFactory = SyncEngineFactory { uid, clock -> DefaultSyncEngine(uid, clock) }

    val sessionController: SyncSessionController = AndroidSyncSessionController(app)
    val prefs = UserPrefs(app)

    /** Exact-title IDs for the room's "Play in Netflix" (none with the debug fakes). */
    val titleLookup: TitleLookup = firebase?.titleLookup ?: TitleLookup.None

    val social: SocialServices = firebase?.let { fb ->
        SocialServices(
            auth = fb.auth,
            profiles = fb.profiles,
            friends = fb.friends,
            dms = fb.dms,
            presence = fb.presence,
            watchInvites = fb.watchInvites,
            activityTracker = AndroidActivityTracker(gateway, fb.presence, appScope),
            push = fb.push,
            avatars = fb.avatars,
        )
    } ?: DebugSocial.createFake(now = transport.serverClock::serverNowMs)

    /** Presence + activity tracker while signed in. */
    val socialSession = SocialSession(social, prefs, sessionController, transport, appScope)

    /** "Watched together" cards in DMs, while the sync session runs (any screen). */
    val watchTogether = WatchTogetherRecorder(social, sessionController, transport, appScope)

    /** Room voice chat through our relay (unsupported with the debug fakes). */
    val voice: VoiceController = AndroidVoiceController(app, firebase?.voiceAuth)

    init {
        // members/{me}/voice follows the voice chat, so the others see who's talking.
        appScope.launch {
            var shown: String? = null
            voice.state
                .map { s -> s.roomCode?.takeIf { s.status == VoiceStatus.CONNECTED && it != VOICE_ECHO_ROOM } }
                .distinctUntilChanged()
                .collect { code ->
                    shown?.takeIf { it != code }?.let { old -> runCatching { transport.setInVoice(old, false) } }
                    code?.let { runCatching { transport.setInVoice(it, true) } }
                    shown = code
                }
        }
    }
}
