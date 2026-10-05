package com.syncit.core.config

/**
 * Package names of the streaming apps we control through MediaSession.
 */
object StreamingConfig {
    const val NETFLIX_PACKAGE = "com.netflix.mediaclient"

    // Read from the live media session (`adb shell dumpsys media_session`) with JioHotstar
    // v26.09.14.1 playing on an OPPO CPH2607 / Android 16, 2026-10-06. JioHotstar kept the old
    // Hotstar package name.
    const val JIOHOTSTAR_PACKAGE = "in.startv.hotstar"
}
