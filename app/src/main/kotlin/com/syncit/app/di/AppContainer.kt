package com.syncit.app.di

import android.app.Application
import com.syncit.app.data.UserPrefs
import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncSessionController
import com.syncit.core.sync.SyncEngineFactory
import com.syncit.core.transport.SyncTransport
import com.syncit.firebase.FirebaseServices
import com.syncit.firebase.FirebaseSyncTransport
import com.syncit.media.AndroidMediaControllerGateway
import com.syncit.media.activity.AndroidActivityTracker
import com.syncit.media.service.AndroidSyncSessionController
import com.syncit.sync.DefaultSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ======================== ORCHESTRATOR SWITCH: social backend ========================
 * true  = in-memory fakes (FakeSocial: sample friends, nothing leaves the phone). Rooms don't
 *         work in this mode: the v2 transport needs a real signed-in account.
 * false = real :data-firebase (FirebaseServices: auth, profiles, friends, DMs, presence,
 *         invites, transport) + :media's AndroidActivityTracker.
 * =====================================================================================
 */
const val USE_FAKE_SOCIAL = false

/**
 * Manual DI: the one place concrete implementations are chosen. Everything else sees only the
 * :core interfaces. To move to a Socket.IO backend, swap [transport] here.
 */
class AppContainer(app: Application) : SyncDependencies {
    /** Lives as long as the process (activity tracker, social session). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val firebase: FirebaseServices? = if (USE_FAKE_SOCIAL) null else FirebaseServices.create(app)

    override val gateway: MediaControllerGateway = AndroidMediaControllerGateway(app)

    /** Shares the signed-in account with [social] when real; the standalone v1 transport otherwise. */
    override val transport: SyncTransport = firebase?.transport ?: FirebaseSyncTransport.create()
    override val engineFactory: SyncEngineFactory = SyncEngineFactory { uid, clock -> DefaultSyncEngine(uid, clock) }

    val sessionController: SyncSessionController = AndroidSyncSessionController(app)
    val prefs = UserPrefs(app)

    val social: SocialServices = firebase?.let { fb ->
        SocialServices(
            auth = fb.auth,
            profiles = fb.profiles,
            friends = fb.friends,
            dms = fb.dms,
            presence = fb.presence,
            watchInvites = fb.watchInvites,
            activityTracker = AndroidActivityTracker(gateway, fb.presence, appScope),
        )
    } ?: FakeSocial.create(now = transport.serverClock::serverNowMs)

    /** Presence + activity tracker while signed in. */
    val socialSession = SocialSession(social, prefs, sessionController, appScope)
}
