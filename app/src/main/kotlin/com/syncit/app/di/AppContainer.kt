package com.syncit.app.di

import android.app.Application
import com.syncit.app.data.UserPrefs
import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncSessionController
import com.syncit.core.sync.SyncEngineFactory
import com.syncit.core.transport.SyncTransport
import com.syncit.firebase.FirebaseSyncTransport
import com.syncit.media.AndroidMediaControllerGateway
import com.syncit.media.service.AndroidSyncSessionController
import com.syncit.sync.DefaultSyncEngine

/**
 * Manual DI: the one place concrete implementations are chosen. Everything else sees only the
 * :core interfaces. To move to a Socket.IO backend, swap [transport] here.
 */
class AppContainer(app: Application) : SyncDependencies {
    override val gateway: MediaControllerGateway = AndroidMediaControllerGateway(app)
    override val transport: SyncTransport = FirebaseSyncTransport.create()
    override val engineFactory: SyncEngineFactory = SyncEngineFactory { uid, clock -> DefaultSyncEngine(uid, clock) }

    val sessionController: SyncSessionController = AndroidSyncSessionController(app)
    val prefs = UserPrefs(app)
}
