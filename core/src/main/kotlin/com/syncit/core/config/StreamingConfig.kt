package com.syncit.core.config

/**
 * Package names of the media apps we read (and, for video apps, control) through MediaSession.
 * Netflix and JioHotstar were confirmed from live media sessions. The others were confirmed
 * installed on the user's OPPO (`pm list packages`, 2026-10-15); what their media sessions
 * publish still has to be checked on the device (Media diagnostics) before relying on it.
 */
object StreamingConfig {
    const val NETFLIX_PACKAGE = "com.netflix.mediaclient"

    // Read from the live media session (`adb shell dumpsys media_session`) with JioHotstar
    // v26.09.14.1 playing on an OPPO CPH2607 / Android 16, 2026-10-06. JioHotstar kept the old
    // Hotstar package name.
    const val JIOHOTSTAR_PACKAGE = "in.startv.hotstar"

    const val PRIME_VIDEO_PACKAGE = "com.amazon.avod.thirdpartyclient"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    const val AMAZON_MUSIC_PACKAGE = "com.amazon.mp3"
}
