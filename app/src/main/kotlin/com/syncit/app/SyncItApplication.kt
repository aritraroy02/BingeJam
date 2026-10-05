package com.syncit.app

import android.app.Application
import com.syncit.app.di.AppContainer
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncDependenciesProvider

class SyncItApplication : Application(), SyncDependenciesProvider {

    lateinit var container: AppContainer
        private set

    override val syncDependencies: SyncDependencies get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
