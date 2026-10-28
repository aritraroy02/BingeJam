package com.syncit.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.crossfade
import com.syncit.app.di.AppContainer
import com.syncit.app.push.PushNotifications
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncDependenciesProvider

class SyncItApplication : Application(), SyncDependenciesProvider, SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override val syncDependencies: SyncDependencies get() = container

    override fun onCreate() {
        super.onCreate()
        // Before any push can arrive: background FCM notifications name these channels.
        PushNotifications.createChannels(this)
        container = AppContainer(this)
    }

    /** Coil's shared loader for profile pictures; animated GIFs play everywhere an avatar is shown. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
