package com.syncit.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.crossfade
import com.syncit.app.di.AppContainer
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.push.PushNotifications
import com.syncit.core.session.AppQuitHandler
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncDependenciesProvider
import com.syncit.media.MediaListenerService

class SyncItApplication : Application(), SyncDependenciesProvider, AppQuitHandler, SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override val syncDependencies: SyncDependencies get() = container

    override fun onCreate() {
        super.onCreate()
        // Before any push can arrive: background FCM notifications name these channels.
        PushNotifications.createChannels(this)
        container = AppContainer(this)
    }

    /**
     * "Quit" from the sync notification. Stays quit until MainActivity opens again: the room
     * membership is kept (others see us offline), push notifications for messages still arrive.
     */
    override fun quitApp() {
        // SocialSession sees this and takes presence offline / stops activity sharing.
        container.prefs.setQuit(true)
        container.sessionController.stop()
        ChatBubbleService.stop(this)
        // Otherwise Android keeps the listener bound and the process alive (and restarts it).
        MediaListenerService.unbind(this)
        getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        // Give the services' onDestroy and the presence "offline" write a moment, then end the
        // process. Ending it also closes the Firebase connection, so the server's onDisconnect
        // marks us offline in the room even if that write didn't make it.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, QUIT_DELAY_MS)
    }

    /** Coil's shared loader for profile pictures; animated GIFs play everywhere an avatar is shown. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()

    private companion object {
        const val QUIT_DELAY_MS = 1_000L
    }
}
